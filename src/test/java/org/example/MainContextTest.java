package org.example;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file:context-test?mode=memory&cache=shared")
class MainContextTest {

    @Test
    void contextLoads() {
    }
}
