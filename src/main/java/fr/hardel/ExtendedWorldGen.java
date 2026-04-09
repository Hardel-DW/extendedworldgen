package fr.hardel;

import fr.hardel.worldgen.biomeentry.BiomeEntryRegistry;
import fr.hardel.worldgen.parity.BiomeEntryParityCheck;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExtendedWorldGen implements ModInitializer {
	public static final String MOD_ID = "extendedworldgen";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		BiomeEntryRegistry.bootstrap();
		BiomeEntryParityCheck.registerIfEnabled();
	}
}