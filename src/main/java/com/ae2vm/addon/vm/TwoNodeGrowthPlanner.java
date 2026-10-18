package com.ae2vm.addon.vm;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.CraftingSimulationState;
import com.ae2vm.addon.mixin.CraftingSimulationStateAccessor;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;

/** Local patch: exact P: d D + externals -> q R, Q: 1 R -> 1 D, q>d.
 * Handles growth pairs under ordinary DAGs, accounting for the primary valid input of tagged slots.
 * Does not manufacture seeds or replace machines. Execution also needs the feedback
 * reservation mixin: native AE2 otherwise delivers R before Q can reuse it.
 */
public final class TwoNodeGrowthPlanner {
    private record Line(IPatternDetails pattern, AEKey output, long amount, Map<AEKey,Long> inputs) {}
    private record Ring(Line production, Line recycle, AEKey dust, long input) {}
    private static Line line(IPatternDetails p) {
        if (p==null || p.getOutputs()==null || p.getOutputs().size()!=1) return null;
        var out=p.getPrimaryOutput();
        if(out==null || out.what()==null || out.amount()<=0) return null;
        Map<AEKey,Long> inputs=new LinkedHashMap<>();
        if(p.getInputs()==null) return null;
        for(var in:p.getInputs()) {
            var choices=in.getPossibleInputs();
            // A crafting pattern with canSubstitute=true may expose an entire tag.
            // Account conservatively for the encoded primary, which is an actual
            // valid input. Do NOT abort traversal before visiting its producers.
            // The emitted task remains the original pattern, not a synthetic one.
            if(choices==null || choices.length==0 || choices[0]==null) return null;
            var s=choices[0];
            if(s.what()==null || s.amount()<=0 || in.getMultiplier()<=0 || in.getRemainingKey(s.what())!=null) return null;
            inputs.merge(s.what(),Math.multiplyExact(s.amount(),in.getMultiplier()),Math::addExact);
        }
        return new Line(p,out.what(),out.amount(),inputs);
    }
    private static Ring find(IPatternDetails p,Function<AEKey,IPatternDetails> resolver) {
        Line production=line(p);
        if(production==null || production.inputs.size()<2 || production.inputs.containsKey(production.output))return null;
        Ring found=null;
        for(var e:production.inputs.entrySet()) {
            Line back=line(resolver.apply(e.getKey()));
            if(back==null || !back.output.equals(e.getKey()) || back.amount!=1 || back.inputs.size()!=1
                || back.inputs.getOrDefault(production.output,0L)!=1 || production.amount<=e.getValue())continue;
            if(found!=null)return null; // ambiguous rings: leave to the original engine
            found=new Ring(production,back,e.getKey(),e.getValue());
        }
        return found;
    }
    public static boolean containsRing(Collection<IPatternDetails> patterns) {
        Map<AEKey,IPatternDetails> byOutput=new HashMap<>();
        for(var p:patterns) if(p!=null && p.getPrimaryOutput()!=null)byOutput.put(p.getPrimaryOutput().what(),p);
        try { for(var p:patterns)if(find(p,byOutput::get)!=null)return true; }
        catch(ArithmeticException ignored) {}
        return false;
    }
    private static long ceil(BigInteger n,long d) {
        if(n.signum()<=0)return 0;
        return n.add(BigInteger.valueOf(d-1)).divide(BigInteger.valueOf(d)).longValueExact();
    }
    /** Recyclers must dispatch before ordinary consumers of their feedback key.
     * Native ExecutingCraftingJob uses a HashMap, whose arbitrary iteration can
     * otherwise starve a loop nested under e.g. four 32-crystal device crafts.
     */
    public static Map<IPatternDetails,?> prioritizeFeedback(Map<IPatternDetails,?> tasks) {
        Map<AEKey,IPatternDetails> byOutput=new HashMap<>();
        tasks.keySet().forEach(p->byOutput.put(p.getPrimaryOutput().what(),p));
        Map<IPatternDetails,Object> ordered=new LinkedHashMap<>();
        for(var p:tasks.keySet()) {
            Ring r=find(p,byOutput::get);
            if(r!=null && tasks.containsKey(r.recycle.pattern))ordered.put(r.recycle.pattern,tasks.get(r.recycle.pattern));
        }
        tasks.forEach(ordered::put);
        return ordered;
    }
    private static final class Graph {
        final Function<AEKey,IPatternDetails> resolver;
        final Function<AEKey,Long> stock;
        final Set<AEKey> expand,pruned=new HashSet<>();
        final Map<AEKey,Line> lines=new HashMap<>();
        final Map<AEKey,Ring> rings=new HashMap<>();
        final Set<AEKey> seen=new HashSet<>(),active=new HashSet<>();
        final List<AEKey> postorder=new ArrayList<>();
        Graph(Function<AEKey,IPatternDetails> r,Function<AEKey,Long> s,Set<AEKey> e){resolver=r;stock=s;expand=e;}
        void visit(AEKey key,IPatternDetails preferred) {
            if(active.contains(key) || seen.size()>256)throw new UnsupportedOperationException();
            if(seen.contains(key))return;
            seen.add(key);active.add(key);
            if(preferred==null && !expand.contains(key) && stock.apply(key)>0){
                pruned.add(key);active.remove(key);postorder.add(key);return;
            }
            var pattern=preferred!=null?preferred:resolver.apply(key);
            Line l=line(pattern);
            if(pattern!=null && (l==null || !l.output.equals(key)))throw new UnsupportedOperationException();
            if(l!=null){
                lines.put(key,l);Ring ring=find(pattern,resolver);
                if(ring!=null)rings.put(key,ring);
                for(var child:l.inputs.keySet())if(ring==null || !child.equals(ring.dust))visit(child,null);
            }
            active.remove(key);postorder.add(key);
        }
    }
    public static ICraftingPlan tryPlan(CraftingBytecode request,Function<AEKey,IPatternDetails> resolver,
            Function<AEKey,Long> stock,CraftingSimulationState simulation) {
        return tryPlan(request,resolver,stock,simulation,true);
    }
    static ICraftingPlan tryPlan(CraftingBytecode request,Function<AEKey,IPatternDetails> resolver,
            Function<AEKey,Long> stock,CraftingSimulationState simulation,boolean requireGrowth) {
        if(resolver==null || request.getPatternPool().length==0 || request.getOutputAmountPerCraft()<=0)return null;
        try {
            Set<AEKey> expand=new HashSet<>();
            BigInteger extraForReturnedSeed=BigInteger.ZERO;
            for(int pass=0;pass<257;pass++) {
            Graph graph=new Graph(resolver,stock,expand);
            graph.visit(request.getOutput(),request.getPatternPool()[0]);

            // A seed that is independently consumed elsewhere in the graph needs a
            // more general joint allocator; do not claim support for that topology.
            for(var ring:graph.rings.values())if(graph.seen.contains(ring.dust)
                    && !ring.dust.equals(request.getOutput()))throw new UnsupportedOperationException();
            Builder b=new Builder(stock,resolver,null);
            Map<AEKey,BigInteger> demand=new HashMap<>();
            demand.put(request.getOutput(),BigInteger.valueOf(request.getOutputAmountPerCraft()).add(extraForReturnedSeed));
            Collections.reverse(graph.postorder);
            boolean retry=false;
            for(AEKey key:graph.postorder){
                long n=demand.getOrDefault(key,BigInteger.ZERO).longValueExact();
                if(n<=0)continue;
                Ring ring=graph.rings.get(key);
                boolean root=key.equals(request.getOutput());
                long held=b.available(key);
                long stockDelivery=0;
                if(!root){
                    // Reserve only the working capital needed to bootstrap the ring.
                    long seedReserve=ring==null || held>=n?0:Math.min(held,Math.max(0,ring.input-b.available(ring.dust)));
                    stockDelivery=Math.min(n,held-seedReserve);b.take(key,stockDelivery);n-=stockDelivery;
                }
                if(n==0)continue;
                if(graph.pruned.contains(key)){expand.add(key);retry=true;break;}
                if(ring!=null){
                    long d=ring.input,q=ring.production.amount;
                    long direct=ceil(BigInteger.valueOf(n),q);
                    long dust=Math.min(b.available(ring.dust),Math.multiplyExact(d,direct));
                    long crystalSeed=Math.min(b.available(key),Math.max(0,d-dust));
                    long missingSeed=Math.max(0,d-dust-crystalSeed);
                    long effectiveDust=Math.addExact(dust,missingSeed);
                    long k=Math.max(direct,ceil(BigInteger.valueOf(n).subtract(BigInteger.valueOf(effectiveDust))
                            .subtract(BigInteger.valueOf(root?0:crystalSeed)),q-d));
                    long recycled=Math.max(0,Math.subtractExact(Math.multiplyExact(d,k),effectiveDust));
                    b.take(ring.dust,dust);b.take(key,crystalSeed);
                    if(missingSeed>0)b.missing.add(ring.dust,missingSeed);
                    b.add(ring.production.pattern,k);b.add(ring.recycle.pattern,recycled);
                    for(var e:ring.production.inputs.entrySet())if(!e.getKey().equals(ring.dust))
                        demand.merge(e.getKey(),BigInteger.valueOf(e.getValue()).multiply(BigInteger.valueOf(k)),BigInteger::add);
                }else{
                    Line l=graph.lines.get(key);
                    if(l==null){b.missing.add(key,n);continue;}
                    long crafts=ceil(BigInteger.valueOf(n),l.amount);b.add(l.pattern,crafts);
                    for(var e:l.inputs.entrySet())demand.merge(e.getKey(),
                            BigInteger.valueOf(e.getValue()).multiply(BigInteger.valueOf(crafts)),BigInteger::add);
                }
            }
            if(retry)continue;
            if(requireGrowth && graph.rings.isEmpty())return null;
            // Net growth, not merely gross delivery: replace any target-key seed
            // consumed while traversing another node of the ring (e.g. root=dust).
            var net=NetProduction.generated(b.patterns,request.getOutput(),new KeyCounter());
            BigInteger desired=BigInteger.valueOf(request.getOutputAmountPerCraft());
            if(net.isPresent() && net.get().compareTo(desired)<0 && b.used.get(request.getOutput())>0){
                extraForReturnedSeed=extraForReturnedSeed.add(desired.subtract(net.get()));continue;
            }
            // No simulation mutation before the entire supported plan exists.
            simulation.addBytes((b.patterns.size()+1)*8.0);
            for(var e:b.patterns.entrySet())for(var in:line(e.getKey()).inputs.entrySet())
                simulation.addStackBytes(in.getKey(),in.getValue(),e.getValue());
            long bytes=Math.max(1,(long)Math.ceil(((CraftingSimulationStateAccessor)simulation).getBytes()));
            return new CraftingPlan(new GenericStack(request.getOutput(),request.getOutputAmountPerCraft()),
                    bytes,!b.missing.isEmpty(),false,b.used,new KeyCounter(),b.missing,new LinkedHashMap<>(b.patterns));
            }
            return null;
        }catch(UnsupportedOperationException|ArithmeticException ex){return null;}
    }
    private static final class Builder {
        final Function<AEKey,Long> stock;
        final KeyCounter used=new KeyCounter(),missing=new KeyCounter();
        final Map<AEKey,Long> consumed=new HashMap<>();
        final Map<IPatternDetails,Long> patterns=new LinkedHashMap<>();
        Builder(Function<AEKey,Long> s,Function<AEKey,IPatternDetails> unused,Ring pair){stock=s;}
        long available(AEKey k){return Math.max(0,stock.apply(k)-consumed.getOrDefault(k,0L));}
        void take(AEKey k,long n){if(n>0){consumed.merge(k,n,Math::addExact);used.add(k,n);}}
        void add(IPatternDetails p,long n){if(n>0)patterns.merge(p,n,Math::addExact);}
    }
}