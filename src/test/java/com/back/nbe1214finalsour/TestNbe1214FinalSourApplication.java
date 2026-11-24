package com.back.nbe1214finalsour;

import org.springframework.boot.SpringApplication;

public class TestNbe1214FinalSourApplication {

    static void main(String[] args) {
        SpringApplication.from(Nbe1214FinalSourApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
