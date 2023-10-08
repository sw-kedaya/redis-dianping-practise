package com.hmdp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;

@SpringBootTest(classes = HmDianPingApplication.class)
public class MyTest {
    @Test
    void name() {
        String name = "abcde";
        int hash = name.hashCode();
        System.out.println(hash); // 92599395 -> ‭101 1000 0100 1111 0100 0110 0011‬
        int d1 = hash & 0xF; // ‭101 1000 0100 1111 0100 0110 0011‬ & 1111(0xF)
        System.out.println(d1); // 0011 & 1111 = 0011 = 3
        int d2 = hash >> 4 & 0xF; // ‭101 1000 0100 1111 0100 0110‬ & 1111(0xF)
        System.out.println(d2); // 0110‬ & 1111 = 0110 = 6
    }

    @Test
    void skip() {
        ArrayList<Integer> list = new ArrayList<>();
        for (int i = 1; i < 10; i++) {
            list.add(i);
        }
        list.forEach(System.out::println);
        System.out.println("--------------------");
        list.stream().skip(5).forEach(System.out::println);
    }
}
