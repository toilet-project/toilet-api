package com.example.toiletapi.growth;

import java.util.List;

public final class AdminGrowthRankingModels {
    private AdminGrowthRankingModels() { }
    public static final String SCOPE="ACTIVE_AND_SUSPENDED";
    public static final List<String> EXCLUDED_STATUSES=List.of("PENDING_CONSENT","WITHDRAWN");
    private static final com.fasterxml.jackson.databind.ObjectMapper PRIVATE_JSON=new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public static SearchRequest parse(byte[] body) {
        try {return PRIVATE_JSON.readValue(body,SearchRequest.class);}
        catch(java.io.IOException | RuntimeException ignored) {throw new IllegalArgumentException("경험치 조회 조건을 확인해 주세요.");}
    }
    /** POST transports private search text; this operation never writes or reconciles XP. */
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=SearchRequestDeserializer.class)
    public record SearchRequest(String keyword,String sort,Integer page,Integer size) { }
    public static final class SearchRequestDeserializer extends com.fasterxml.jackson.databind.JsonDeserializer<SearchRequest> {
        @Override public SearchRequest deserialize(com.fasterxml.jackson.core.JsonParser parser,com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            com.fasterxml.jackson.databind.JsonNode node=parser.getCodec().readTree(parser);
            if(!node.isObject())throw invalid(parser);
            var fields=node.fieldNames();while(fields.hasNext())if(!java.util.Set.of("keyword","sort","page","size").contains(fields.next()))throw invalid(parser);
            return new SearchRequest(text(node,"keyword",parser),text(node,"sort",parser),integer(node,"page",parser),integer(node,"size",parser));
        }
        private static String text(com.fasterxml.jackson.databind.JsonNode node,String key,com.fasterxml.jackson.core.JsonParser parser) throws java.io.IOException {
            var value=node.get(key);if(value==null || value.isNull())return null;if(!value.isTextual())throw invalid(parser);return value.textValue();
        }
        private static Integer integer(com.fasterxml.jackson.databind.JsonNode node,String key,com.fasterxml.jackson.core.JsonParser parser) throws java.io.IOException {
            var value=node.get(key);if(value==null || value.isNull())return null;if(!value.isIntegralNumber() || !value.canConvertToInt())throw invalid(parser);return value.intValue();
        }
        private static com.fasterxml.jackson.databind.JsonMappingException invalid(com.fasterxml.jackson.core.JsonParser parser) {return com.fasterxml.jackson.databind.JsonMappingException.from(parser,"경험치 조회 조건을 확인해 주세요.");}
    }
    public record Item(String id,String displayName,String email,String status,long totalXp,int level,
                       String rank,String rankName,long position) { }
    public record Page(List<Item> items,int page,int size,long totalElements,int totalPages,
                       String scope,List<String> excludedStatuses) { }
    public record Bucket(String rank,String rankName,long minXp,Long maxXp,long count) { }
    public record Distribution(long totalMembers,List<Bucket> buckets,String scope,List<String> excludedStatuses) { }
}
