package com.gs.ais;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import com.gs.ais.config.HibernateNativeHints;

@SpringBootApplication
@EnableJpaAuditing
@ImportRuntimeHints(HibernateNativeHints.class)
public class AisApplication {

	public static void main(String[] args) {
		// GraalVM / server environments: AWT thumbnail generation must run headless.
		System.setProperty("java.awt.headless", "true");
		SpringApplication.run(AisApplication.class, args);
	}

}
