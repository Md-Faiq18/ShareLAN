package com.lanshare.parser;
import java.util.regex.*;import java.util.function.Function;
/** Grammar: LIST FILES | SHOW HISTORY | STATUS | SEARCH quoted-text | HELP. */
public final class CommandInterpreter {
 private static final Pattern SEARCH=Pattern.compile("(?i)^SEARCH\\s+\"([^\"]{1,100})\"$");
 public String execute(String input,Function<String,String> search,Function<String,String> data){String s=input==null?"":input.trim();if(s.equalsIgnoreCase("LIST FILES"))return data.apply("files");if(s.equalsIgnoreCase("SHOW HISTORY"))return data.apply("history");if(s.equalsIgnoreCase("STATUS"))return data.apply("status");if(s.equalsIgnoreCase("HELP"))return "Commands: LIST FILES, SHOW HISTORY, STATUS, SEARCH \"text\", HELP";Matcher m=SEARCH.matcher(s);if(m.matches())return search.apply(m.group(1));return "Invalid command. Type HELP.";}
}
