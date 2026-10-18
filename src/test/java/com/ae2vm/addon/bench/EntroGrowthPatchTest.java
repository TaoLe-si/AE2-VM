package com.ae2vm.addon.bench;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

public class EntroGrowthPatchTest {
 static final BenchAEKey R=BenchAEKey.of("entro_crystal"),D=BenchAEKey.of("entro_dust"),
     F=BenchAEKey.of("fluix"),W=BenchAEKey.of("water"),RAW=BenchAEKey.of("fluix_raw");
 record Fixture(Map<AEKey,IPatternDetails> patterns,Map<BenchAEKey,Long> stock){}
 static Fixture fixture(long dust,long crystal,long fluix,boolean craftFluix){
   var p=new BenchPatternDetails(R,64,List.of(BenchPatternDetails.InputSpec.of(D,32),
       BenchPatternDetails.InputSpec.of(F,32),BenchPatternDetails.InputSpec.of(W,500)));
   var q=new BenchPatternDetails(D,1,List.of(BenchPatternDetails.InputSpec.of(R,1)));
   Map<AEKey,IPatternDetails> patterns=new HashMap<>();patterns.put(R,p);patterns.put(D,q);
   if(craftFluix)patterns.put(F,new BenchPatternDetails(F,4,List.of(BenchPatternDetails.InputSpec.of(RAW,3))));
   Map<BenchAEKey,Long> stock=new HashMap<>();stock.put(D,dust);stock.put(R,crystal);stock.put(F,fluix);
   stock.put(W,100000000L);stock.put(RAW,1000000L);return new Fixture(patterns,stock);
 }
 static ICraftingPlan plan(Fixture f,long amount){
   PatternCompiler.clearCache();PatternCompiler.clearFuzzyGroups();
   f.patterns.values().forEach(PatternCompiler::compileIfAbsent);
   return new CraftingVM(new FakeBenchGrid(new HashMap<>(f.stock)),f.patterns::get)
       .execute(PatternCompiler.compileRequest(f.patterns.get(R),amount),new BenchSimulationState(f.stock));
 }
 @TestFactory Stream<DynamicTest> seededRequests(){
   List<DynamicTest> tests=new ArrayList<>();
   for(long d:new long[]{0,1,16,31,32,33,64,1024})for(long r:new long[]{0,1,16,31,32,64})
     for(long n:new long[]{1,63,64,65,128,1024}){
       long dust=d,crystal=r,amount=n;
       tests.add(DynamicTest.dynamicTest("seed-"+d+"-"+r+"-"+n,()->{
         var f=fixture(dust,crystal,1000000,false);var result=plan(f,amount);
         boolean feasible=dust+crystal>=32;
         assertEquals(!feasible,result.simulation());
         if(feasible){
           assertTrue(result.missingItems().isEmpty());
           for(boolean reverse:new boolean[]{false,true})executeCpuModel(result,reverse,true);
         }else assertEquals(32-dust-crystal,result.missingItems().get(D));
       }));
     }
   return tests.stream();
 }
 @Test void exact128Counts(){
   var f=fixture(32,0,1000000,false);var p=plan(f,128);
   assertFalse(p.simulation());assertEquals(32,p.usedItems().get(D));assertEquals(96,p.usedItems().get(F));
   assertEquals(1500,p.usedItems().get(W));assertEquals(3,p.patternTimes().get(f.patterns.get(R)));
   assertEquals(64,p.patternTimes().get(f.patterns.get(D)));
 }
 @Test void noFreeSeedAndNoFakeLeaf(){
   var f=fixture(0,0,0,false);var p=plan(f,128);assertTrue(p.simulation());assertEquals(32,p.missingItems().get(D));
   assertTrue(p.missingItems().get(F)>0);
 }
 @Test void externalCraftingDependency(){
   var f=fixture(32,0,0,true);var p=plan(f,128);assertFalse(p.simulation());
   assertEquals(72,p.usedItems().get(RAW));assertEquals(24,p.patternTimes().get(f.patterns.get(F)));
   executeCpuModel(p,true,true);
 }
 @Test void warmVmResnapshotsStock(){
   var f=fixture(32,0,1000000,false);PatternCompiler.clearCache();
   f.patterns.values().forEach(PatternCompiler::compileIfAbsent);
   var vm=new CraftingVM(new FakeBenchGrid(f.stock),f.patterns::get);
   var req=PatternCompiler.compileRequest(f.patterns.get(R),128);
   assertFalse(vm.execute(req,new BenchSimulationState(f.stock)).simulation());
   f.stock.put(D,0L);
   assertTrue(vm.execute(req,new BenchSimulationState(f.stock)).simulation());
 }
 @Test void nativeDeliveryReallyDeadlocks(){
   var p=plan(fixture(32,0,1000000,false),128);
   assertThrows(AssertionError.class,()->executeCpuModel(p,false,false));
 }
 @Test void largeRequestAlgebraic(){
   var p=plan(fixture(32,0,Long.MAX_VALUE,false),1000000000L);
   // Water is genuinely missing: no fabricated leaf stock, but no false recursive dust deficit.
   assertEquals(0,p.missingItems().get(D));assertTrue(p.missingItems().get(W)>0);
 }
 @Test void nestedDeviceAndSharedDiamondDemand(){
   var f=fixture(32,0,1000000,false);
   BenchAEKey device=BenchAEKey.of("device"), a=BenchAEKey.of("a"), b=BenchAEKey.of("b");
   f.patterns.put(a,new BenchPatternDetails(a,1,List.of(BenchPatternDetails.InputSpec.of(R,16))));
   f.patterns.put(b,new BenchPatternDetails(b,1,List.of(BenchPatternDetails.InputSpec.of(R,16))));
   f.patterns.put(device,new BenchPatternDetails(device,1,List.of(BenchPatternDetails.InputSpec.of(a,1),BenchPatternDetails.InputSpec.of(b,1))));
   PatternCompiler.clearCache();f.patterns.values().forEach(PatternCompiler::compileIfAbsent);
   var p=new CraftingVM(new FakeBenchGrid(f.stock),f.patterns::get).execute(
       PatternCompiler.compileRequest(f.patterns.get(device),4),new BenchSimulationState(f.stock));
   assertFalse(p.simulation());assertEquals(3,p.patternTimes().get(f.patterns.get(R)));
   for(boolean reverse:new boolean[]{false,true})executeCpuModel(p,reverse,true);
 }
 @Test void requestedDustAlsoWorks(){
   var f=fixture(32,0,1000000,false);
   PatternCompiler.clearCache();f.patterns.values().forEach(PatternCompiler::compileIfAbsent);
   var p=new CraftingVM(new FakeBenchGrid(f.stock),f.patterns::get).execute(
       PatternCompiler.compileRequest(f.patterns.get(D),128),new BenchSimulationState(f.stock));
   assertFalse(p.simulation());
   for(boolean reverse:new boolean[]{false,true})executeCpuModel(p,reverse,true);
 }
 @Test void nestedRootStockIsNotMistakenForFreeDelivery(){
   var f=fixture(0,32,1000000,false);var device=BenchAEKey.of("device");
   f.patterns.put(device,new BenchPatternDetails(device,1,List.of(BenchPatternDetails.InputSpec.of(R,32))));
   PatternCompiler.clearCache();f.patterns.values().forEach(PatternCompiler::compileIfAbsent);
   var p=new CraftingVM(new FakeBenchGrid(f.stock),f.patterns::get).execute(
       PatternCompiler.compileRequest(f.patterns.get(device),4),new BenchSimulationState(f.stock));
   assertFalse(p.simulation());assertEquals(32,p.usedItems().get(R));
   executeCpuModel(p,true,true);
 }
 @Test void stockedUnsupportedRecipeIsNotExpanded(){
   var f=fixture(32,0,1000000,false);
   f.patterns.put(F,new BenchPatternDetails(F,4,List.of(BenchPatternDetails.InputSpec.of(RAW,1)),
       List.of(new BenchPatternDetails.OutputSpec(BenchAEKey.of("byproduct"),1))));
   var p=plan(f,128);assertFalse(p.simulation());assertEquals(96,p.usedItems().get(F));
 }
 @Test void reservationLimits(){
   assertEquals(0,FeedbackReservation.reserve(64,64,0,0));
   assertEquals(32,FeedbackReservation.reserve(64,64,64,32));
   assertEquals(4,FeedbackReservation.reserve(64,4,64,0));
 }
 record Packet(AEKey key,long amount){}
 /** Mirrors native waiting-for gating, arbitrary task iteration, finite per-tick dispatch,
  * asynchronous and reordered machine return packets, and immediate final-output delivery.
  * It intentionally does NOT allow final product back into the CPU without the patch.
  */
 static void executeCpuModel(ICraftingPlan p,boolean reverse,boolean reserveFeedback){
   Map<AEKey,Long> inv=new HashMap<>(),waiting=new HashMap<>();
   for(var e:p.usedItems())inv.put(e.getKey(),e.getLongValue());
   Map<IPatternDetails,Long> todo=new LinkedHashMap<>(p.patternTimes());
   var order=new ArrayList<>(todo.keySet());if(reverse)Collections.reverse(order);
   if(reserveFeedback){
     Map<IPatternDetails,Object> taskOrder=new LinkedHashMap<>();order.forEach(pat->taskOrder.put(pat,new Object()));
     order=new ArrayList<>(TwoNodeGrowthPlanner.prioritizeFeedback(taskOrder).keySet());
   }
   List<Packet> pending=new ArrayList<>();long delivered=0;int idle=0;
   long restoreTarget=reserveFeedback && TwoNodeGrowthPlanner.containsRing(p.patternTimes().keySet())
       ?p.usedItems().get(p.finalOutput().what()):0;
   for(int tick=0;tick<200000;tick++){
     boolean progress=false;
     // One operation per tick matches a single CPU without co-processors.
     for(var pattern:order){
       if(todo.getOrDefault(pattern,0L)<=0)continue;
       Map<AEKey,Long> need=new HashMap<>();for(var in:pattern.getInputs()){
         var s=in.getPossibleInputs()[0];need.merge(s.what(),s.amount()*in.getMultiplier(),Long::sum);
       }
       if(need.entrySet().stream().anyMatch(e->inv.getOrDefault(e.getKey(),0L)<e.getValue()))continue;
       need.forEach((k,v)->inv.merge(k,-v,Long::sum));todo.merge(pattern,-1L,Long::sum);
       for(var out:pattern.getOutputs()){
         waiting.merge(out.what(),out.amount(),Long::sum);pending.add(new Packet(out.what(),out.amount()));
       }
       progress=true;break;
     }
     // Half-rate return; LIFO vs FIFO exercises different machine latency orders.
     if(!pending.isEmpty() && tick%2==1){
       var packet=pending.remove(reverse?pending.size()-1:0);var key=packet.key;long count=packet.amount;
       assertTrue(waiting.getOrDefault(key,0L)>=count);waiting.merge(key,-count,Long::sum);
       if(key.equals(p.finalOutput().what())){
         long inputDemand=0;
         for(var e:todo.entrySet())for(var in:e.getKey().getInputs()){
           var s=in.getPossibleInputs()[0];if(s.what().equals(key))inputDemand+=s.amount()*in.getMultiplier()*e.getValue();
         }
         long held=reserveFeedback?FeedbackReservation.reserve(count,count,inputDemand,inv.getOrDefault(key,0L)):0;
         long restored=Math.min(count-held,restoreTarget);restoreTarget-=restored;
         inv.merge(key,held+restored,Long::sum);delivered+=count-held-restored;
       }else inv.merge(key,count,Long::sum);
       progress=true;
     }
     if(delivered>=p.finalOutput().amount()){
       assertTrue(delivered+inv.getOrDefault(p.finalOutput().what(),0L)-p.usedItems().get(p.finalOutput().what())>=p.finalOutput().amount(),"delivered old target stock instead of net growth");return;
     }
     if(progress)idle=0;else if(++idle>4)fail("CPU deadlocked: delivered="+delivered+" inv="+inv+" todo="+todo.values());
   }
   fail("CPU operation budget exceeded");
 }
}
