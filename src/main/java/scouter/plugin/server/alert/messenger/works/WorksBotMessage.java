package scouter.plugin.server.alert.messenger.works;

public class WorksBotMessage {
    public Content content;

    public static class Content {
        public String type;
        public String text;
        public String altText;
        public FlexContent contents;
    }

    public static class FlexContent {
        public String type = "bubble";
        public Box header;
        public Box body;
        public Box footer;
    }

    public static class Box {
        public String type = "box";
        public String layout;
        public String backgroundColor;
        public String spacing;
        public BoxContent[] contents;
    }

    public static class BoxContent {
        public String type;
        public String text;
        public String weight;
        public String color;
        public String size;
        public Boolean wrap;
        public String align;
        public String margin;
        public String offsetStart;
        public String offsetEnd;
        public String offsetTop;
        public String offsetBottom;
    }
}