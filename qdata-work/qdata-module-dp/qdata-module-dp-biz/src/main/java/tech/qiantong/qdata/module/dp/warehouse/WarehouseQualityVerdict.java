package tech.qiantong.qdata.module.dp.warehouse;

import java.util.*;

/** Critical rules govern acceptance independently of averaged scores and issue handling states. */
final class WarehouseQualityVerdict {
    record Result(boolean passed,long ruleHits,long blockingHits) { }
    static Result evaluate(List<Map<String,Object>> results,Set<Long> expected,Set<Long> blocking,long dataRows) {
        Set<Long> observed=new HashSet<>();long hits=0,critical=0;boolean valid=true;
        for(var row:results) {
            long id=Long.parseLong(String.valueOf(row.get("evaluate_id")));
            long total=((Number)row.get("total")).longValue(),bad=((Number)row.get("problem_total")).longValue();
            valid &= observed.add(id) && expected.contains(id) && total>=0 && bad>=0 && bad<=total;
            hits+=bad;
            if(blocking.contains(id)) { critical+=bad;if(total==0 && dataRows>0)valid=false; }
        }
        valid &= observed.equals(expected) && !blocking.isEmpty() && expected.containsAll(blocking) && critical==0;
        return new Result(valid,hits,critical);
    }
}
