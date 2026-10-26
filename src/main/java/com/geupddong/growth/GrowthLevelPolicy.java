package com.geupddong.growth;

import java.util.List;

/** Pure display policy shared by member summaries, public author ranks and admin reporting. */
public final class GrowthLevelPolicy {
    private GrowthLevelPolicy() { }
    public record Tier(String rank,String rankName,int firstLevel) {
        public long minXp() {return threshold(firstLevel);}
    }
    public static final List<Tier> TIERS=List.of(
            new Tier("white","흰색 휴지",1),new Tier("green","초록 휴지",3),
            new Tier("yellow","노랑 휴지",5),new Tier("blue","파랑 휴지",10),
            new Tier("red","빨강 휴지",15),new Tier("pink","핑크 휴지",25),
            new Tier("black","검정 휴지",40));
    public static Tier tier(long xp) {
        for(int i=TIERS.size()-1;i>0;i--)if(xp>=TIERS.get(i).minXp())return TIERS.get(i);
        return TIERS.getFirst();
    }
    public static long threshold(int level) {return 10L*(level*(long)level-1);}
    public static int level(long xp) {
        long units=Math.max(0,xp)/10;
        int level=(int)Math.sqrt(units+1);
        // Comparing before multiplication by ten also handles BIGINT's upper bound.
        while((level+1)*(long)(level+1)-1<=units)level++;
        while(level>1 && level*(long)level-1>units)level--;
        return Math.max(1,level);
    }
}
