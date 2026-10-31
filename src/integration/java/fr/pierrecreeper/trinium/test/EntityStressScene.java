package fr.pierrecreeper.trinium.probe;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;

/**
 * Isolated offline renderer workload. NoAI/NoGravity deliberately remove mob movement and
 * AI variability; these results measure rendering, not ordinary survival/server simulation.
 * Never package this helper in the production mod or install it in a user's profile.
 */
public final class EntityStressScene {
    public static final String TAG="trinium_entity_stress";
    private EntityStressScene() { }

    public static void setup(Minecraft minecraft) {
        var server=minecraft.getSingleplayerServer();
        if(server==null)throw new IllegalStateException("Entity stress scene requires an isolated single-player test world");
        server.execute(()->{
            var source=server.createCommandSourceStack();
            var commands=server.getCommands();
            java.util.function.Consumer<String> run=command->commands.performPrefixedCommand(source,command);
            run.accept("gamemode spectator @a");
            run.accept("gamerule minecraft:spawn_mobs false");
            run.accept("kill @e[tag="+TAG+"]");
            run.accept("kill @e[type=!minecraft:player,x=20,y=79,z=56,dx=44,dy=12,dz=32]");
            run.accept("fill 20 79 56 64 79 88 minecraft:stone");
            run.accept("fill 20 80 56 64 88 88 minecraft:air");
            for(int column=0;column<12;column++)for(int row=0;row<9;row++) {
                int x=24+column*3,z=60+row*3;
                run.accept("setblock "+x+" 80 "+z+" minecraft:chest[facing=south]");
                if(((column+row)&1)==0) {
                    run.accept("summon minecraft:cow "+(x+1.5)+" 80 "+(z+1.5)+
                        " {Tags:[\""+TAG+"\"],NoAI:1b,NoGravity:1b,Motion:[0d,0d,0d],PersistenceRequired:1b,Silent:1b}");
                } else {
                    run.accept("summon minecraft:armor_stand "+(x+1.5)+" 80 "+(z+1.5)+
                        " {Tags:[\""+TAG+"\"],NoGravity:1b,Motion:[0d,0d,0d],ShowArms:1b,Pose:{Head:[15f,25f,0f],LeftArm:[-20f,0f,-15f],RightArm:[-35f,0f,15f]}}");
                }
                run.accept("summon minecraft:experience_orb "+(x+.5)+" 81.25 "+(z+.5)+
                    " {Tags:[\""+TAG+"\"],NoGravity:1b,Motion:[0d,0d,0d],Value:1,Age:0s}");
                run.accept("summon minecraft:item "+(x+1.5)+" 81 "+(z+.5)+
                    " {Tags:[\""+TAG+"\"],NoGravity:1b,Motion:[0d,0d,0d],PickupDelay:32767s,Age:0s,Item:{id:\"minecraft:diamond\",count:1}}");
            }
            run.accept("tp @a 42.5 89 108 180 25");
            System.out.println("TRINIUM_ENTITY_STRESS_SETUP 108 chests, 54 armor stands, 54 cows, 108 XP orbs, 108 items; offline rendering-only NoAI/NoGravity fixture");
        });
    }

    /** Client-side counts in the cleared fixture bounds; command tags are server-only. */
    public static Map<String,Integer> snapshot(Minecraft minecraft) {
        Map<String,Integer> counts=new LinkedHashMap<>();
        for(String key:new String[]{"chests","armorStands","cows","xpOrbs","items","otherEntities","sceneEntities"})counts.put(key,0);
        if(minecraft.level==null)return counts;
        int chests=0;
        for(int column=0;column<12;column++)for(int row=0;row<9;row++)
            if(minecraft.level.getBlockState(new BlockPos(24+column*3,80,60+row*3)).is(Blocks.CHEST))chests++;
        counts.put("chests",chests);
        for(var entity:minecraft.level.entitiesForRendering()) {
            if(entity.getType()==EntityTypes.PLAYER || entity.getX()<20 || entity.getX()>65 || entity.getY()<79 || entity.getY()>92 || entity.getZ()<56 || entity.getZ()>89)continue;
            var type=entity.getType();
            String key=type==EntityTypes.ARMOR_STAND?"armorStands":type==EntityTypes.COW?"cows":
                type==EntityTypes.EXPERIENCE_ORB?"xpOrbs":type==EntityTypes.ITEM?"items":"otherEntities";
            counts.put(key,counts.get(key)+1);
            counts.put("sceneEntities",counts.get("sceneEntities")+1);
        }
        return counts;
    }
}
