package com.back.nbe1214finalsour;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CiSmokeUnitTest {

    @Test
    @DisplayName("JUnit과 AssertJ가 동작한다")
    void assertjWorks() {
        assertThat(1 + 1).isEqualTo(2);
    }

    @Test
    @DisplayName("Mockito가 동작한다")
    void mockitoWorks() {
        @SuppressWarnings("unchecked")
        Supplier<String> supplier = mock(Supplier.class);
        given(supplier.get()).willReturn("ok");

        assertThat(supplier.get()).isEqualTo("ok");
    }

//    @Test
//    @DisplayName("실패하기 실험")
//    void t1(){
//        assertThat(1).isEqualTo(2);
//    }
}