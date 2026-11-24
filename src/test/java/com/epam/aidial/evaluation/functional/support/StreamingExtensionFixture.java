package com.epam.aidial.evaluation.functional.support;

/** Synthetic stream matching the combined runner assembly test. */
public final class StreamingExtensionFixture {
    public static final String FIRST = """
            {"choices":[{"delta":{"content":"Hello","custom_content":{
              "stages":[{"index":0,"name":"Search","content":"Read ","attachments":[{"index":0,"title":"re","data":"AA","url":"files/report"}]}],
              "annotations":[{"index":0,"body":{"quote":"content "}}],"state":{"old":1},"form_schema":{"old":1}},
              "custom_fields":{"annotations":[{"index":0,"body":{"quote":"fields "}}],"state":{"old":1}}}}]}
            """.strip().replace("\n", "");
    public static final String SECOND = """
            {"choices":[{"delta":{"content":" world","custom_content":{
              "stages":[{"index":0,"name":"ing","content":"this","attachments":[{"index":0,"title":"port","data":"BB"}]}],
              "annotations":[{"index":0,"body":{"quote":"ref"}}],"state":{"new":2,"optional":null},"form_schema":{"new":2,"optional":null}},
              "custom_fields":{"annotations":[{"index":0,"body":{"quote":"ref"}}],"state":{"new":2}}}}]}
            """.strip().replace("\n", "");
    public static final String ASSEMBLED = """
            {"choices":[{"message":{"role":"assistant","content":"Hello world",
             "custom_content":{"stages":[{"index":0,"name":"Searching","content":"Read this",
               "attachments":[{"index":0,"title":"report","data":"AABB","url":"files/report"}]}],
               "annotations":[{"index":0,"body":{"quote":"content ref"}}],
               "state":{"new":2,"optional":null},"form_schema":{"new":2,"optional":null}},
             "custom_fields":{"annotations":[{"index":0,"body":{"quote":"fields ref"}}],
               "state":{"old":1,"new":2}}},"finish_reason":"stop","index":0}]}
            """;
    public static final String EVENTS = """
            [{"event":"message","data":%s},{"event":"message","data":%s}]
            """.formatted(FIRST, SECOND);

    private StreamingExtensionFixture() {}
}
