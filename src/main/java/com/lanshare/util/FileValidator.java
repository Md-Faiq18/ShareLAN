package com.lanshare.util;
import java.util.regex.Pattern;
public final class FileValidator {
 private static final Pattern NAME=Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._()\\-]{0,179}");
 private FileValidator(){}
 public static boolean validFilename(String name){return name!=null && NAME.matcher(name).matches() && !name.equals(".") && !name.equals("..");}
 public static boolean validPort(int p){return p>=1&&p<=65535;}
}
