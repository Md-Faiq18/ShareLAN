package com.lanshare.service;
import java.io.IOException;import java.nio.file.*;import java.util.*;
/** Independent recursion demonstration over a directory tree. */
public final class FolderWalker {
    private FolderWalker(){}
    public static List<Path> files(Path root)
            throws IOException {List<Path> out=new ArrayList<>();
        walk(root,out);
        return out;}
    private static void walk(Path p,List<Path> out)
            throws IOException {if(Files.isDirectory(p)){
                try(DirectoryStream<Path> children=Files.newDirectoryStream(p))
                {for(Path child:children)walk(child,out);}}
    else if(Files.isRegularFile(p))out.add(p);}}
