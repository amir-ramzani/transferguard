package io.github.nulled.transferguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** TransferGuard (codename Pengawal): real-time scam and mule-account screening for bank transfers. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class TransferGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransferGuardApplication.class, args);
    }
}
