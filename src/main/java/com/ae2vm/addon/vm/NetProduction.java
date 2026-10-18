package com.ae2vm.addon.vm;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import java.math.BigInteger;
import java.util.*;
/** Stoichiometric NEW production. Existing target stock is never credited as gain. */
public final class NetProduction {
 public static Optional<BigInteger> generated(Map<IPatternDetails,Long> patterns,AEKey target,KeyCounter emitted){
   BigInteger net=BigInteger.valueOf(emitted.get(target));
   for(var task:patterns.entrySet()){
     BigInteger per=BigInteger.ZERO;
     for(var out:task.getKey().getOutputs())if(target.equals(out.what()))per=per.add(BigInteger.valueOf(out.amount()));
     for(var in:task.getKey().getInputs()){
       var options=in.getPossibleInputs();
       if(options==null || options.length==0)continue;
       boolean hasTarget=Arrays.stream(options).anyMatch(s->s!=null && target.equals(s.what()));
       if(!hasTarget)continue;
       // A tag is not a reason to abandon accounting: subtract the maximum
       // possible target-key consumption. This is a conservative LOWER bound
       // on net growth, never an optimistic credit from choosing a substitute.
       if(target.equals(in.getRemainingKey(target)))continue;
       long targetAmount=0;
       for(var option:options)if(option!=null && target.equals(option.what()))
           targetAmount=Math.max(targetAmount,option.amount());
       per=per.subtract(BigInteger.valueOf(targetAmount).multiply(BigInteger.valueOf(in.getMultiplier())));
     }
     net=net.add(per.multiply(BigInteger.valueOf(task.getValue())));
   }
   return Optional.of(net);
 }
 public static boolean sufficient(ICraftingPlan p){
   var net=generated(p.patternTimes(),p.finalOutput().what(),p.emittedItems());
   return net.isPresent() && net.get().compareTo(BigInteger.valueOf(p.finalOutput().amount()))>=0;
 }
 private NetProduction(){}
}
