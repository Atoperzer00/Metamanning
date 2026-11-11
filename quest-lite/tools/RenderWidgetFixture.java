package com.metamanning.questlite;

import java.nio.file.Files;
import java.nio.file.Path;

/** Test driver uses the actual Android renderer's pure Java HTML builder. */
public final class RenderWidgetFixture {
    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of(args[0]));
        System.out.print(WidgetDocument.render(source, Boolean.parseBoolean(args[1])));
    }
}
