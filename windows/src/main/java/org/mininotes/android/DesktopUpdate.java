package org.mininotes.android;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.function.IntConsumer;
import java.util.zip.*;

/**
 * A newer Mininotes for Windows: found, fetched, checked and put in place of this one, from inside the app.
 *
 * <p>The repository keeps one line, {@code dist/latest-windows.txt}, with the newest Windows version; every
 * release carries that build as {@code Mininotes-Windows-<version>.zip} with its SHA-256 beside it, so the
 * zip is found from the version alone under {@code releases/latest/download}. Nothing is sent with any
 * request. The zip is kept only if it matches its checksum; then it is opened beside this app, and once
 * Mininotes has closed a small script puts it where this app was - same folder, so shortcuts still work -
 * and starts it. The notebook is not in that folder and is not touched. If anything goes wrong the old
 * folder is put back and started instead.
 */
final class DesktopUpdate {
    static final String SOURCE="https://github.com/mininotesorg/mininotes";
    static final String LATEST=SOURCE.replace("github.com","raw.githubusercontent.com")+"/main/dist/latest-windows.txt";
    static final String RELEASES=SOURCE+"/releases/latest";
    private static final long MOST=400L*1024*1024;

    private DesktopUpdate(){}

    private static final HttpClient HTTP=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();

    /** The newest published version, or "" when it could not be read. Blocking. */
    static String newest(){try{return latest();}catch(IOException no){return "";}}

    /** The newest published version, or why it could not be known, in words for the person. Blocking. */
    static String latest() throws IOException {
        HttpResponse<String> said;
        try{said=HTTP.send(HttpRequest.newBuilder(URI.create(LATEST)).timeout(Duration.ofSeconds(20)).GET().build(),HttpResponse.BodyHandlers.ofString());}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("The check was stopped.");}
        catch(IOException offline){throw new IOException("GitHub could not be reached. Check the connection and try again.");}
        if(said.statusCode()==404)throw new IOException("No version for Windows has been published on GitHub yet.");
        if(said.statusCode()!=200)throw new IOException("GitHub did not answer the check (HTTP "+said.statusCode()+").");
        String version=Update.read(said.body().lines().findFirst().orElse(""));
        if(version.isEmpty())throw new IOException("GitHub's answer was not a version.");
        return version;
    }

    /** Where the zip of a version is, and its checksum beside it. */
    static String zip(String version){String v=Update.read(version);return v.isEmpty()?"":RELEASES+"/download/Mininotes-Windows-"+v+".zip";}

    /** This app's own folder - where Mininotes.exe is - or null when it is not running as the packaged app. */
    static Path appFolder() {
        String exe=System.getProperty("jpackage.app-path");
        if(exe==null||exe.isBlank())return null;
        Path folder=Path.of(exe).toAbsolutePath().getParent();
        return folder!=null&&Files.isRegularFile(folder.resolve("Mininotes.exe"))?folder:null;
    }

    /**
     * The zip of that version, downloaded and checked against its SHA-256, then opened beside this app.
     * Blocking; {@code percent} hears how far the download is.
     *
     * @return the opened app, ready to be put in place
     */
    static Path fetch(String version,Path app,IntConsumer percent) throws Exception {
        String url=zip(version);
        if(url.isEmpty())throw new IOException("That is not a version.");
        HttpResponse<String> sum=HTTP.send(HttpRequest.newBuilder(URI.create(url+".sha256")).timeout(Duration.ofSeconds(30)).GET().build(),HttpResponse.BodyHandlers.ofString());
        String expected=sum.statusCode()==200?Update.digest(sum.body()):"";
        if(expected.isEmpty())throw new IOException("The release has no checksum for this version, so nothing was installed.");
        Path zip=Files.createTempFile("mininotes-update-",".zip");
        try {
            HttpResponse<InputStream> got=HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            if(got.statusCode()!=200)throw new IOException("The release has no Windows build for "+version+" (HTTP "+got.statusCode()+").");
            long size=got.headers().firstValueAsLong("content-length").orElse(-1);
            MessageDigest sha=MessageDigest.getInstance("SHA-256");
            try(InputStream in=got.body();OutputStream out=Files.newOutputStream(zip)) {
                byte[] buffer=new byte[1<<16];long total=0;int read,shown=-1;
                while((read=in.read(buffer))>0) {
                    total+=read;if(total>MOST)throw new IOException("The download is larger than any Mininotes build.");
                    sha.update(buffer,0,read);out.write(buffer,0,read);
                    int now=size>0?(int)(total*100/size):-1;if(now!=shown){shown=now;percent.accept(now);}
                }
            }
            if(!Update.hex(sha.digest()).equals(expected))throw new IOException("The download does not match its checksum, so nothing was installed.");
            Path staged=app.resolveSibling("."+app.getFileName()+".next");
            delete(staged);Files.createDirectories(staged);
            unzip(zip,staged);
            Path opened=staged.resolve("Mininotes");
            if(!Files.isRegularFile(opened.resolve("Mininotes.exe")))throw new IOException("The download is not a Mininotes build.");
            return opened;
        } finally {Files.deleteIfExists(zip);}
    }

    /** Every entry, kept inside the folder: a name that climbs out of it refuses the whole zip. */
    static void unzip(Path zip,Path into) throws IOException {
        Path root=into.toAbsolutePath().normalize();
        try(ZipInputStream in=new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
            for(ZipEntry entry;(entry=in.getNextEntry())!=null;) {
                Path target=root.resolve(entry.getName().replace('\\','/')).normalize();
                if(!target.startsWith(root)||target.equals(root))throw new IOException("The download has a file that would land outside its folder.");
                if(entry.isDirectory()){Files.createDirectories(target);continue;}
                Files.createDirectories(target.getParent());
                Files.copy(in,target,StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * The script that waits for this Mininotes to close, puts the new one where it was and starts it -
     * or, if any step fails, puts the old one back and starts that. Started now, hidden; the caller then
     * closes Mininotes.
     */
    static void replaceAfterExit(Path app,Path opened) throws IOException{replaceAfterExit(app,opened,ProcessHandle.current().pid(),true);}

    /** @param start whether to open Mininotes afterwards: yes for "Restart to update", no when it was simply closed */
    static Process replaceAfterExit(Path app,Path opened,long waitFor,boolean start) throws IOException {
        String stamp=Long.toString(System.currentTimeMillis());
        Path old=app.resolveSibling("."+app.getFileName()+".old-"+stamp);
        String script=String.join("\r\n",
            "$ErrorActionPreference='Stop'",
            "$app='"+ps(app)+"';$new='"+ps(opened)+"';$old='"+ps(old)+"';$staged='"+ps(opened.getParent())+"';$start=$"+start,
            "try{Wait-Process -Id "+waitFor+" -Timeout 120 -ErrorAction SilentlyContinue}catch{}",
            // Files of a program that has just closed can stay locked for a moment; a few tries.
            "$moved=$false;for($i=0;$i -lt 20 -and -not $moved;$i++){try{Move-Item -LiteralPath $app -Destination $old;$moved=$true}catch{Start-Sleep -Milliseconds 500}}",
            "if(-not $moved){if($start){Start-Process -FilePath (Join-Path $app 'Mininotes.exe')};exit 1}",
            "try{Move-Item -LiteralPath $new -Destination $app;if($start){Start-Process -FilePath (Join-Path $app 'Mininotes.exe')}}",
            "catch{if(Test-Path -LiteralPath $app){Remove-Item -LiteralPath $app -Recurse -Force};Move-Item -LiteralPath $old -Destination $app;if($start){Start-Process -FilePath (Join-Path $app 'Mininotes.exe')};exit 1}",
            "Start-Sleep -Seconds 5;Remove-Item -LiteralPath $old -Recurse -Force -ErrorAction SilentlyContinue;Remove-Item -LiteralPath $staged -Recurse -Force -ErrorAction SilentlyContinue",
            "");
        Path file=Files.createTempFile("mininotes-update-",".ps1");
        // With the byte-order mark: Windows PowerShell reads a file without one in the old code page, and a
        // folder with an accent in its name would be a different folder.
        Files.writeString(file,"﻿"+script,StandardCharsets.UTF_8);
        String shell=Path.of(System.getenv().getOrDefault("SystemRoot","C:\\Windows"),"System32","WindowsPowerShell","v1.0","powershell.exe").toString();
        return new ProcessBuilder(shell,"-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-WindowStyle","Hidden","-File",file.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    /** A path inside single quotes in PowerShell: a quote is written twice. */
    private static String ps(Path path){return path.toAbsolutePath().toString().replace("'","''");}

    private static void delete(Path folder) throws IOException {
        if(!Files.exists(folder))return;
        try(var all=Files.walk(folder)){for(Path one:all.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(one);}
    }
}
