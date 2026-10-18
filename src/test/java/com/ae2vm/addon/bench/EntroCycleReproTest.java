package com.ae2vm.addon.bench;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingVM;
import java.util.*;
import org.junit.jupiter.api.Test;
public class EntroCycleReproTest {
 @Test void reproduce() {
  for(long dustStock: new long[]{0,32,64}) for(long amount: new long[]{64,128,1024}) {
   PatternCompiler.clearCache(); PatternCompiler.clearFuzzyGroups();
   var crystal=BenchAEKey.of("entro_crystal"); var dust=BenchAEKey.of("entro_dust");
   var fluix=BenchAEKey.of("fluix_crystal"); var water=BenchAEKey.of("water");
   var reaction=new BenchPatternDetails(crystal,64,List.of(BenchPatternDetails.InputSpec.of(dust,32),BenchPatternDetails.InputSpec.of(fluix,32),BenchPatternDetails.InputSpec.of(water,500)));
   var grind=new BenchPatternDetails(dust,1,List.of(BenchPatternDetails.InputSpec.of(crystal,1)));
   Map<AEKey,IPatternDetails> patterns=new HashMap<>(); patterns.put(crystal,reaction); patterns.put(dust,grind);
   patterns.values().forEach(PatternCompiler::compileIfAbsent);
   Map<BenchAEKey,Long> stock=new HashMap<>(); stock.put(dust,dustStock); stock.put(fluix,100000L);stock.put(water,10000000L);
   var vm=new CraftingVM(new FakeBenchGrid(new HashMap<>(stock)),patterns::get);
   var plan=vm.execute(PatternCompiler.compileRequest(reaction,amount),new BenchSimulationState(stock));
   var missing=new TreeMap<String,Long>(); for(var e:plan.missingItems())missing.put(((BenchAEKey)e.getKey()).itemId(),e.getLongValue());
   System.out.println("ENTRO_REPRO dust="+dustStock+" request="+amount+" missing="+missing+" simulation="+plan.simulation());
  }
 }
}
