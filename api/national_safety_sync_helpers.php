<?php
declare(strict_types=1);

/**
 * Chooses the next geographic block without starving later states.
 * During the first national fill it prefers the state with the lowest completion ratio;
 * after full coverage it refreshes the oldest successful block.
 */
function national_safety_next_balanced_chunk(?string $onlyUf=null): ?array {
    $onlyUf=$onlyUf!==null?strtoupper(trim($onlyUf)):null;
    if($onlyUf!==null&&!ep2_valid_uf($onlyUf))return null;
    $status=road_hazard_chunk_status_map();
    $ufs=$onlyUf!==null?[$onlyUf]:ep2_brazil_ufs();
    $candidate=null;$candidateRatio=INF;$candidateDone=PHP_INT_MAX;
    $oldest=null;$oldestTs=PHP_INT_MAX;

    foreach($ufs as $uf){
        $chunks=road_hazard_state_chunks($uf);$done=0;$firstMissing=null;
        foreach($chunks as $chunk){
            $row=$status[$chunk['key']]??null;
            if(!$row||empty($row['last_success'])){if($firstMissing===null)$firstMissing=$chunk;continue;}
            $done++;$ts=strtotime((string)$row['last_success']);if($ts===false)$ts=0;
            if($ts<$oldestTs){$oldestTs=$ts;$oldest=$chunk;}
        }
        if($firstMissing!==null){
            $total=max(1,count($chunks));$ratio=$done/$total;
            if($candidate===null||$ratio<$candidateRatio-0.0000001||(abs($ratio-$candidateRatio)<0.0000001&&$done<$candidateDone)){
                $candidate=$firstMissing;$candidateRatio=$ratio;$candidateDone=$done;
            }
        }
    }
    return $candidate??$oldest;
}
