package com.dtech.algo.mapper;

public interface GenericMapper<S, T>{
    S reverse(T source);
    T map(S destination);
}
