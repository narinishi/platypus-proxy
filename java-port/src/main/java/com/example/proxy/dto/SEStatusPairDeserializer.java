package com.example.proxy.dto;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

import java.lang.reflect.Type;
import java.util.Map;

public class SEStatusPairDeserializer implements JsonDeserializer<SEStatusPair> {
    @Override
    public SEStatusPair deserialize(JsonElement json, Type typeOfT,
                                    JsonDeserializationContext context) throws JsonParseException {
        Map<String, String> map = context.deserialize(json, Map.class);
        if (map.size() != 1) {
            throw new JsonParseException("ambiguous status: " + map);
        }
        Map.Entry<String, String> entry = map.entrySet().iterator().next();
        return new SEStatusPair(Long.parseLong(entry.getKey()), entry.getValue());
    }
}
