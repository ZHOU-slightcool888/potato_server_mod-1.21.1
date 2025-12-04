package potato_server_mod.modid;


import net.fabricmc.api.ModInitializer;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import potato_server_mod.modid.item.FoodServerPotato;


public class Potato_server_mod implements ModInitializer {
    public static final String MOD_ID = "potato_server_mod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);


    @Override
    public void onInitialize() {
// 注册物品（1.21.1 正确的注册方式）
        Registry.register(
                Registries.ITEM,
                Identifier.of(MOD_ID, "server_potato"),
                new FoodServerPotato()
        );
        LOGGER.info("Potato Server Mod loaded!");
    }
}