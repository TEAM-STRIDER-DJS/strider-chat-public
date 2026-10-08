package com.strider.chat.model.response;

public record Envelope<T> (
        T data,
        Meta meta
){
    public static record Meta(
            String status,
            Integer code,
            String message
    ){}
}