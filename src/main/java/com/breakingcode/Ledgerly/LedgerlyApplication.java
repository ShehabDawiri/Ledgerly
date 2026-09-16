package com.breakingcode.Ledgerly;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LedgerlyApplication {
	public static void main(String[] args) {
		SpringApplication.run(LedgerlyApplication.class, args);
	}
}
