package com.ae2vm.addon.vm;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.crafting.inv.CraftingSimulationState;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.mixin.CraftingSimulationStateAccessor;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

/** Bounded breadth-first search over encoded-pattern assignments, not repeated
 * item-by-item tree expansion. Each supported candidate is solved algebraically,
 * with stock and seed accounting, then compared by total recipe executions.
 * Tagged inputs are conservatively planned using the encoded primary variant.
 * This is not an unrestricted global optimizer: per-key mixed recipes, full tag-
 * variant optimization, byproducts and larger growth rings are outside its scope.
 */
public final class AlternativePatternSearch {
    public static final int DEFAULT_STATES=512;
    public static final long DEFAULT_NANOS=200_000_000L;
    private static final int MAX_QUEUE=4096, MAX_CHOICES=16;
    public record Result(ICraftingPlan plan,int evaluated,boolean truncated,BigInteger operations) {}
    private final Function<AEKey,IPatternDetails> preferred;
    private final Function<AEKey,List<IPatternDetails>> all;
    private final Function<AEKey,Long> stock;
    private final Map<AEKey,List<IPatternDetails>> candidates=new LinkedHashMap<>();
    private final Map<AEKey,Long> frozenStock=new HashMap<>();
    private final Map<AEKey,Integer> ids=new HashMap<>();
    private boolean clipped;
    private AlternativePatternSearch(Function<AEKey,IPatternDetails> p,
            Function<AEKey,List<IPatternDetails>> a,Function<AEKey,Long> s) {preferred=p;all=a;stock=s;}
    public static Result search(CraftingBytecode request,Function<AEKey,IPatternDetails> preferred,
            Function<AEKey,List<IPatternDetails>> all,Function<AEKey,Long> stock) {
        return search(request,preferred,all,stock,DEFAULT_STATES,DEFAULT_NANOS);
    }
    public static Result search(CraftingBytecode request,Function<AEKey,IPatternDetails> preferred,
            Function<AEKey,List<IPatternDetails>> all,Function<AEKey,Long> stock,int maxStates,long nanos) {
        if(all==null || request.getPatternPool().length==0 || request.getPatternPool()[0].getPrimaryOutput()==null
                || !request.getOutput().equals(request.getPatternPool()[0].getPrimaryOutput().what()))return new Result(null,0,false,null);
        return new AlternativePatternSearch(preferred,all,stock).run(request,maxStates,nanos);
    }
    private List<IPatternDetails> choices(AEKey key,IPatternDetails rootDefault) {
        return candidates.computeIfAbsent(key,k->{
            ids.put(k,ids.size());
            LinkedHashSet<IPatternDetails> set=new LinkedHashSet<>();
            IPatternDetails first=rootDefault!=null?rootDefault:preferred.apply(k);
            if(first!=null && first.getPrimaryOutput()!=null && k.equals(first.getPrimaryOutput().what()))set.add(first);
            var others=all.apply(k);if(others!=null)for(var p:others)if(p!=null && p.getPrimaryOutput()!=null
                    && k.equals(p.getPrimaryOutput().what()))set.add(p);
            List<IPatternDetails> result=new ArrayList<>(set);
            if(result.size()>MAX_CHOICES){clipped=true;result=new ArrayList<>(result.subList(0,MAX_CHOICES));}
            return List.copyOf(result);
        });
    }
    private String signature(Map<AEKey,Integer> selection) {
        return selection.entrySet().stream().filter(e->e.getValue()!=0)
            .sorted(Comparator.comparingInt(e->ids.get(e.getKey())))
            .map(e->ids.get(e.getKey())+":"+e.getValue()).reduce("",(a,b)->a+";"+b);
    }
    private Result run(CraftingBytecode request,int maxStates,long nanos) {
        long started=System.nanoTime();
        AEKey output=request.getOutput();
        choices(output,request.getPatternPool()[0]);
        Deque<Map<AEKey,Integer>> queue=new ArrayDeque<>();queue.add(Map.of());
        Set<String> seen=new HashSet<>();seen.add("");
        ICraftingPlan best=null;BigInteger bestCost=null;int evaluated=0;
        while(!queue.isEmpty() && evaluated<Math.max(1,maxStates)) {
            if(Thread.currentThread().isInterrupted())throw new CancellationException("AE2-VM alternative search interrupted");
            if(evaluated>0 && System.nanoTime()-started>=nanos){clipped=true;break;}
            Map<AEKey,Integer> selection=queue.removeFirst();
            Set<AEKey> touched=new LinkedHashSet<>();touched.add(output);
            Function<AEKey,IPatternDetails> resolver=key->{
                touched.add(key);var list=choices(key,null);
                if(list.isEmpty())return null;
                return list.get(selection.getOrDefault(key,0));
            };
            var root=choices(output,request.getPatternPool()[0]).get(selection.getOrDefault(output,0));
            // Candidate evaluation must not mutate the caller's crafting inventory,
            // byte count, cached plans, or the stock view of another candidate.
            var compiled=PatternCompiler.compileRequest(root,request.getOutputAmountPerCraft());
            ICraftingPlan plan=TwoNodeGrowthPlanner.tryPlan(compiled,resolver,
                    key->frozenStock.computeIfAbsent(key,k->Math.max(0L,stock.apply(k))),new CostState(),false);
            evaluated++;
            if(plan!=null && !plan.simulation() && plan.missingItems().isEmpty() && NetProduction.sufficient(plan)) {
                BigInteger cost=operations(plan);
                if(bestCost==null || cost.compareTo(bestCost)<0){best=plan;bestCost=cost;}
            }
            // Continue after finding a plan: BFS depth counts changed recipe
            // choices, NOT actual craft executions. Stopping at the first plan
            // would not satisfy the operation-count objective.
            for(AEKey key:touched) {
                var list=choices(key,null);int current=selection.getOrDefault(key,0);
                for(int index=0;index<list.size();index++)if(index!=current){
                    Map<AEKey,Integer> next=new HashMap<>(selection);
                    if(index==0)next.remove(key);else next.put(key,index);
                    String sig=signature(next);
                    if(seen.add(sig)){
                        if(queue.size()<MAX_QUEUE)queue.addLast(Map.copyOf(next));else clipped=true;
                    }
                }
            }
        }
        return new Result(best,evaluated,clipped || !queue.isEmpty(),bestCost);
    }
    public static BigInteger operations(ICraftingPlan plan) {
        BigInteger count=BigInteger.ZERO;
        for(long times:plan.patternTimes().values())count=count.add(BigInteger.valueOf(times));
        return count;
    }
    /** Only AE2 byte accounting is used by candidate construction. */
    private static final class CostState extends CraftingSimulationState implements CraftingSimulationStateAccessor {
        @Override protected long simulateExtractParent(AEKey key,long amount){return 0;}
        @Override protected Iterable<AEKey> findFuzzyParent(AEKey key){return List.of(key);}
        private static final java.lang.reflect.Field BYTES;
        static {
            try{BYTES=CraftingSimulationState.class.getDeclaredField("bytes");BYTES.setAccessible(true);}
            catch(ReflectiveOperationException e){throw new ExceptionInInitializerError(e);}
        }
        @Override public double getBytes(){
            try{return BYTES.getDouble(this);}
            catch(IllegalAccessException e){throw new IllegalStateException(e);}
        }
    }
}
