package awszqsedxzaw.cloudarchive;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CloudArchiveMod implements ClientModInitializer {
    public static final String MOD_ID = "cloudarchive";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("CloudArchive initialized");
    }
}
