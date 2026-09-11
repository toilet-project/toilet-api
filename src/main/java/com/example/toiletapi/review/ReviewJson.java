package com.example.toiletapi.review;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Scoped to review request fields: never silently truncate a fractional star, ID, minute or version. */
public final class ReviewJson {
    private ReviewJson() { }
    public static final class IntegerValue extends ValueDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser parser,DeserializationContext context) {
            if(parser.currentToken()!=JsonToken.VALUE_NUMBER_INT)return context.reportInputMismatch(Integer.class,"Expected integer");
            return parser.getIntValue();
        }
    }
    public static final class LongValue extends ValueDeserializer<Long> {
        @Override public Long deserialize(JsonParser parser,DeserializationContext context) {
            if(parser.currentToken()!=JsonToken.VALUE_NUMBER_INT)return context.reportInputMismatch(Long.class,"Expected integer");
            return parser.getLongValue();
        }
    }
    public static final class BooleanValue extends ValueDeserializer<Boolean> {
        @Override public Boolean deserialize(JsonParser parser,DeserializationContext context) {
            if(parser.currentToken()==JsonToken.VALUE_TRUE)return true;
            if(parser.currentToken()==JsonToken.VALUE_FALSE)return false;
            return context.reportInputMismatch(Boolean.class,"Expected boolean");
        }
    }
    public static final class StringValue extends ValueDeserializer<String> {
        @Override public String deserialize(JsonParser parser,DeserializationContext context) {
            if(parser.currentToken()!=JsonToken.VALUE_STRING)return context.reportInputMismatch(String.class,"Expected text");
            return parser.getString();
        }
    }
}
