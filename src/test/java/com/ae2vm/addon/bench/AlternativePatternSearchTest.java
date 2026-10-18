package com.ae2vm.addon.bench;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import com.ae2vm.addon.vm.*;
import com.ae2vm.addon.compiler.PatternCompiler;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AlternativePatternSearchTest {
 static final BenchAEKey R=BenchAEKey.of("purified_resonating_crystal"),D=BenchAEKey.of("resonating_dust"),
   S=BenchAEKey.of("resonating_seed"),RAW=BenchAEKey.of("raw"),MID=BenchAEKey.of("mid"),F=BenchAEKey.of("fluix"),W=BenchAEKey.of("water");
 static IPatternDetails p(BenchAEKey out,long count,Object... pairs){
   var in=new ArrayList<BenchPatternDetails.InputSpec>();
   for(int i=0;i<pairs.length;i+=2)in.add(BenchPatternDetails.InputSpec.of((BenchAEKey)pairs[i],((Number)pairs[i+1]).longValue()));
   return new BenchPatternDetails(out,count,in);
 }
 static class Fixture{
   final Map<AEKey,List<IPatternDetails>> patterns=new LinkedHashMap<>();
   final Map<BenchAEKey,Long> stock=new HashMap<>();
   void add(IPatternDetails p){patterns.computeIfAbsent(p.getPrimaryOutput().what(),k->new ArrayList<>()).add(p);}
   IPatternDetails first(AEKey k){var list=patterns.get(k);return list==null?null:list.get(0);}
   AlternativePatternSearch.Result search(long n){return search(n,512);}
   AlternativePatternSearch.Result search(long n,int states){
     PatternCompiler.clearCache();PatternCompiler.clearFuzzyGroups();
     return AlternativePatternSearch.search(PatternCompiler.compileRequest(first(R),n),this::first,
       patterns::get,k->stock.getOrDefault(k,0L),states,Long.MAX_VALUE);
   }
 }
 static Fixture resonating(){
   var f=new Fixture();f.add(p(R,1,S,1));f.add(p(S,1,D,1));f.add(p(D,1,R,1));
   f.stock.put(R,1000L);f.stock.put(RAW,100000L);return f;
 }
 @Test void threeNodeEqualLoopCannotCountOldCrystalAsNewProduction(){
   var f=resonating();var result=f.search(32);assertNull(result.plan());assertFalse(result.truncated());
 }
 @Test void alternativePowderBreaksThreeNodeLoop(){
   var f=resonating();var alternative=p(D,4,RAW,1);f.add(alternative);
   var result=f.search(32);assertNotNull(result.plan());assertFalse(result.truncated());
   var plan=result.plan();assertTrue(NetProduction.sufficient(plan));assertEquals(0,plan.usedItems().get(R));
   assertEquals(8,plan.usedItems().get(RAW));assertEquals(72,result.operations().longValueExact());
   assertEquals(8,plan.patternTimes().get(alternative));assertFalse(plan.patternTimes().containsKey(f.first(D)));
   assertTrue(plan.bytes()>0);EntroGrowthPatchTest.executeCpuModel(plan,false,true);
 }
 @Test void doNotStopAtFirstFeasiblePath(){
   var f=resonating();var slow=p(D,1,RAW,1);var batch=p(D,16,RAW,1);f.add(slow);f.add(batch);
   var result=f.search(32);assertEquals(66,result.operations().longValueExact());
   assertTrue(result.plan().patternTimes().containsKey(batch));assertFalse(result.plan().patternTimes().containsKey(slow));
 }
 @Test void unavailableAlternativeIsNotFeasible(){
   var f=resonating();f.stock.put(RAW,0L);f.add(p(D,1,RAW,1));assertNull(f.search(32).plan());
 }
 @Test void searchCanChangeRootRecipe(){
   var f=resonating();var direct=p(R,64,RAW,1);f.add(direct);
   var result=f.search(32);assertEquals(1,result.operations().longValueExact());assertEquals(1,result.plan().patternTimes().get(direct));
 }
 @Test void alternativeMayHaveItsOwnPrerequisites(){
   var f=resonating();f.add(p(D,8,MID,1));f.add(p(MID,1,RAW,2));
   var result=f.search(32);assertNotNull(result.plan());assertEquals(8,result.plan().usedItems().get(RAW));
   EntroGrowthPatchTest.executeCpuModel(result.plan(),true,true);
 }
 @Test void trueGrowthRingStillWorks(){
   var f=new Fixture();f.add(p(R,64,D,32,F,32));f.add(p(D,1,R,1));
   f.stock.put(D,32L);f.stock.put(F,100000L);
   var result=f.search(128);assertNotNull(result.plan());assertEquals(67,result.operations().longValueExact());
   EntroGrowthPatchTest.executeCpuModel(result.plan(),true,true);
 }
 @Test void growthAndAlternativePowderCompeteByActualOperations(){
   var f=new Fixture();f.add(p(R,64,D,32,F,32));f.add(p(D,1,R,1));var external=p(D,32,RAW,1);f.add(external);
   f.stock.put(D,32L);f.stock.put(F,100000L);f.stock.put(RAW,100L);
   var result=f.search(128);assertEquals(3,result.operations().longValueExact());
   assertEquals(1,result.plan().patternTimes().get(external));assertEquals(1,result.plan().usedItems().get(RAW));
   EntroGrowthPatchTest.executeCpuModel(result.plan(),false,true);
 }
 @Test void seedCrystalMustBeRestoredBeforeClaimingNetIncrease(){
   var f=new Fixture();f.add(p(R,64,D,32,F,32));f.add(p(D,1,R,1));f.stock.put(R,32L);f.stock.put(F,100000L);
   var result=f.search(128);assertNotNull(result.plan());
   assertEquals(BigInteger.valueOf(128),NetProduction.generated(result.plan().patternTimes(),R,new KeyCounter()).orElseThrow());
   assertEquals(4,result.plan().patternTimes().get(f.first(R)));
   EntroGrowthPatchTest.executeCpuModel(result.plan(),true,true);
 }
 @Test void zeroGainWithExternalCostStillIsNotProduction(){
   var f=new Fixture();f.add(p(R,32,D,32,F,1));f.add(p(D,1,R,1));f.stock.put(D,32L);f.stock.put(F,100L);
   assertNull(f.search(64).plan());
 }
 @Test void noFreeSeedWhenExternalAlternativeUnavailable(){
   var f=new Fixture();f.add(p(R,64,D,32,F,32));f.add(p(D,1,R,1));f.stock.put(F,100000L);
   assertNull(f.search(128).plan());
 }
 @Test void limitIsExplicitAndDoesNotClaimGlobalOptimality(){
   var f=new Fixture();f.add(p(R,1,RAW,1));f.add(p(R,64,RAW,1));f.stock.put(RAW,100L);
   var result=f.search(32,1);assertNotNull(result.plan());assertTrue(result.truncated());assertEquals(32,result.operations().longValueExact());
 }
 @Test void vmFallbackRejectsZeroNetCycleEvenWithTargetStock(){
   var f=resonating();PatternCompiler.clearCache();f.patterns.values().forEach(list->list.forEach(PatternCompiler::compileIfAbsent));
   var vm=new CraftingVM(new FakeBenchGrid(f.stock),f::first);vm.setAlternativePatternsResolver(f.patterns::get);
   var plan=vm.execute(PatternCompiler.compileRequest(f.first(R),32),new BenchSimulationState(f.stock));
   assertTrue(plan.simulation());assertTrue(plan.missingItems().get(R)>0);assertFalse(NetProduction.sufficient(plan));
 }
 @Test void vmUsesLiveAllPatternsNotJustPreferred(){
   var f=resonating();var alt=p(D,4,RAW,1);f.add(alt);
   var vm=new CraftingVM(new FakeBenchGrid(f.stock),f::first);vm.setAlternativePatternsResolver(f.patterns::get);
   var plan=vm.execute(PatternCompiler.compileRequest(f.first(R),32),new BenchSimulationState(f.stock));
   assertFalse(plan.simulation());assertTrue(plan.patternTimes().containsKey(alt));assertTrue(NetProduction.sufficient(plan));
 }
 @Test void selectingBatchRecipeNotJustShortestGraphDepth(){
   var f=new Fixture();f.add(p(R,1,RAW,1));f.add(p(R,64,MID,1));f.add(p(MID,1,RAW,1));f.stock.put(RAW,1000L);
   var result=f.search(64);assertEquals(2,result.operations().longValueExact());assertTrue(NetProduction.sufficient(result.plan()));
 }
 @Test void unknownTargetSubstitutionCannotBeCreditedAsGuaranteedGrowth(){
   var fuzzy=new BenchPatternDetails(R,1,List.of(BenchPatternDetails.InputSpec.fuzzy(R,1,RAW)));
   var plan=new appeng.crafting.CraftingPlan(new appeng.api.stacks.GenericStack(R,1),64,false,false,
       new KeyCounter(),new KeyCounter(),new KeyCounter(),Map.of(fuzzy,1L));
   assertFalse(NetProduction.sufficient(plan));
 }
 @Test void actualTaggedSeedRecipeMustNotHideAlternativeDustProducer(){
   var f=resonating();var sky=BenchAEKey.of("sky_dust");var red=BenchAEKey.of("redstone");
   var seed=new BenchPatternDetails(S,1,List.of(
       BenchPatternDetails.InputSpec.fuzzy(D,1,BenchAEKey.of("other_resonating_dust")),
       BenchPatternDetails.InputSpec.fuzzy(F,1,BenchAEKey.of("other_fluix_dust")),
       BenchPatternDetails.InputSpec.fuzzy(sky,1,BenchAEKey.of("other_sky_dust")),
       BenchPatternDetails.InputSpec.fuzzy(red,1,BenchAEKey.of("other_redstone"))));
   f.patterns.put(S,new ArrayList<>(List.of(seed)));var external=p(D,32,RAW,32);f.add(external);
   f.stock.put(F,100000L);f.stock.put(sky,100000L);f.stock.put(red,100000L);
   for(long requested:new long[]{1,32,100,1000}){
     var result=f.search(requested);assertNotNull(result.plan(),"tagged seed blocked nested dust search at "+requested);
     assertFalse(result.plan().simulation());assertTrue(NetProduction.sufficient(result.plan()));
     assertTrue(result.plan().patternTimes().containsKey(external));
     assertFalse(result.plan().patternTimes().containsKey(f.first(D)));
   }
 }
 @Test void taggedTargetConsumptionUsesConservativeNetLowerBound(){
   var tagged=new BenchPatternDetails(R,2,List.of(BenchPatternDetails.InputSpec.fuzzy(R,1,RAW)));
   assertEquals(BigInteger.ONE,NetProduction.generated(Map.of(tagged,1L),R,new KeyCounter()).orElseThrow());
 }
 @Test void taggedGrowthKeepsFeedbackAndRestoresSeed(){
   for(var target:List.of(R,D)){
     var f=new Fixture();
     f.add(new BenchPatternDetails(R,64,List.of(
         BenchPatternDetails.InputSpec.fuzzy(D,32,BenchAEKey.of("alternate_dust")),
         BenchPatternDetails.InputSpec.of(F,32))));
     f.add(p(D,1,R,1));f.stock.put(D,32L);f.stock.put(F,100000L);
     PatternCompiler.clearCache();f.patterns.values().forEach(list->list.forEach(PatternCompiler::compileIfAbsent));
     var vm=new CraftingVM(new FakeBenchGrid(f.stock),f::first);vm.setAlternativePatternsResolver(f.patterns::get);
     var result=vm.execute(PatternCompiler.compileRequest(f.first(target),128),new BenchSimulationState(f.stock));
     assertFalse(result.simulation());assertTrue(NetProduction.sufficient(result));
     for(boolean reversed:new boolean[]{false,true})EntroGrowthPatchTest.executeCpuModel(result,reversed,true);
   }
 }
 @Test void taggedSeedHandlesExistingSingleDustWithoutHidingAlternatives(){
   var f=resonating();var other=BenchAEKey.of("other_dust");
   f.patterns.put(S,new ArrayList<>(List.of(new BenchPatternDetails(S,1,List.of(BenchPatternDetails.InputSpec.fuzzy(D,1,other))))));
   var external=p(D,32,RAW,32);f.add(external);f.stock.put(D,1L);f.stock.put(R,8L);
   for(long n:new long[]{1,100,1000}){
     var r=f.search(n);assertNotNull(r.plan());assertTrue(NetProduction.sufficient(r.plan()));
     assertEquals(0,r.plan().usedItems().get(R));
     EntroGrowthPatchTest.executeCpuModel(r.plan(),false,true);
   }
 }
 @Test void stockChangeInvalidatesChoice(){
   var f=resonating();var a=p(D,1,RAW,1);var b=p(D,16,F,1);f.add(a);f.add(b);
   var vm=new CraftingVM(new FakeBenchGrid(f.stock),f::first);vm.setAlternativePatternsResolver(f.patterns::get);
   var req=PatternCompiler.compileRequest(f.first(R),32);
   assertFalse(vm.execute(req,new BenchSimulationState(f.stock)).patternTimes().containsKey(b));
   f.stock.put(F,1000L);
   assertTrue(vm.execute(req,new BenchSimulationState(f.stock)).patternTimes().containsKey(b));
 }
}
