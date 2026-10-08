package com.strider.chat.util;

import com.strider.chat.model.response.Envelope;
import com.strider.strider_common_lib.error.StriderErrorCodes;
import com.strider.strider_common_lib.exception.StriderException;

public final class EnvelopeUtils {
    private EnvelopeUtils(){}

    public static <T> T getOrNull(Envelope<T> env) {
        if (env == null || env.meta() == null) return null;
        if (!"success".equals(env.meta().status())) return null;
        return env.data();
    }

    public static <T> T getOrThrow(Envelope<T> env, StriderErrorCodes error) {
        T data = getOrNull(env);
        if (data == null) {
            throw new StriderException(error);
        }
        return data;
    }
}