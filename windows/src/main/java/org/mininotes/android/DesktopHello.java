package org.mininotes.android;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Unlocking with Windows Hello: the PC's own PIN, face or fingerprint instead of the password.
 *
 * <p>Windows Hello keeps a key pair in the PC's security chip and signs with it only after the person has
 * shown their face, finger or PIN. Mininotes asks it to sign one fixed random challenge; the signature is the
 * same every time (RSA, PKCS#1), and its hash is the key that opens {@code vault.hello}: the notebook's key,
 * sealed once more. So Hello alone opens the notebook, and nothing on disk does without it. The password and
 * the twelve words still work; this is another copy, and switching it off deletes it.
 *
 * <p>Java cannot reach Hello itself, so the few calls go through Windows PowerShell, which every Windows 10
 * and 11 has and which can call Windows' own APIs.
 */
final class DesktopHello {
    static final String FILE="vault.hello";
    private static final byte[] MAGIC={'M','N','H','1'};
    private static final SecureRandom RANDOM=new SecureRandom();
    private static volatile Boolean supported;

    private DesktopHello(){}

    static boolean has(Path folder){return Files.isRegularFile(folder.resolve(FILE));}
    static void forget(Path folder){try{Files.deleteIfExists(folder.resolve(FILE));}catch(IOException gone){/* nothing to remove */}}

    /** What {@link #supported} found, if it has been asked yet; null if not. Never blocks. */
    static Boolean known(){return supported;}

    /** Whether this PC has Windows Hello set up. Asked once; blocking. */
    static boolean supported() {
        if(supported!=null)return supported;
        try{supported=run("check","").startsWith("SUPPORTED True");}catch(Exception no){supported=false;}
        return supported;
    }

    /** Hello set up for this notebook: a new challenge signed, and the notebook's key sealed by what it gives. Blocking. */
    static void enable(Path folder,byte[] key) throws Exception {
        byte[] challenge=new byte[32];RANDOM.nextBytes(challenge);
        byte[] sealKey=derive(sign("create",challenge));
        byte[] nonce=new byte[12];RANDOM.nextBytes(nonce);
        Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");aes.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(sealKey,"AES"),new GCMParameterSpec(128,nonce));
        aes.updateAAD(MAGIC);byte[] sealed=aes.doFinal(key);
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(MAGIC);out.write(challenge);out.write(nonce);out.write(sealed);
        org.mininotes.desktop.platform.AtomicFile.write(folder.resolve(FILE),out.toByteArray());
    }

    /** The notebook's key, after Windows Hello has said yes. Blocking while the person answers Hello. */
    static byte[] open(Path folder) throws Exception {
        byte[] all=Files.readAllBytes(folder.resolve(FILE));
        if(all.length<4+32+12+16)throw new IOException("The Windows Hello copy is damaged.");
        for(int i=0;i<4;i++)if(all[i]!=MAGIC[i])throw new IOException("The Windows Hello copy is damaged.");
        byte[] challenge=java.util.Arrays.copyOfRange(all,4,36),nonce=java.util.Arrays.copyOfRange(all,36,48);
        byte[] sealKey=derive(sign("sign",challenge));
        try {
            Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");aes.init(Cipher.DECRYPT_MODE,new SecretKeySpec(sealKey,"AES"),new GCMParameterSpec(128,nonce));
            aes.updateAAD(MAGIC);return aes.doFinal(all,48,all.length-48);
        } catch(javax.crypto.AEADBadTagException changed) {
            throw new IOException("Windows Hello's key for Mininotes has changed. Use your password, then switch Windows Hello on again in Security.");
        }
    }

    private static byte[] derive(byte[] signature) throws Exception {
        MessageDigest sha=MessageDigest.getInstance("SHA-256");sha.update(MAGIC);return sha.digest(signature);
    }

    private static byte[] sign(String how,byte[] challenge) throws Exception {
        String said=run(how,Base64.getEncoder().encodeToString(challenge));
        if(said.startsWith("SIGNED "))return Base64.getDecoder().decode(said.substring(7).trim());
        String status=said.startsWith("STATUS ")?said.substring(7).trim():said.trim();
        throw new IOException(status.equals("UserCanceled")?"Windows Hello was cancelled.":status.equals("NotFound")?"Windows Hello has no key for Mininotes on this PC.":"Windows Hello did not answer ("+status+").");
    }

    /** The PowerShell side: Windows' KeyCredentialManager, asked to check, create, or sign. */
    private static final String SCRIPT=String.join("\n",
        "$ErrorActionPreference='Stop'",
        "Add-Type -AssemblyName System.Runtime.WindowsRuntime",
        // Windows opens Hello's own window for whoever asked - here a hidden helper - so it could open behind
        // Mininotes, where nobody saw it and setting Hello up never finished. Started by the window in use,
        // the helper may let another window come to the front: it lets Hello's, and asks it forward, only a
        // Windows Security window opened since it started.
        "Add-Type -TypeDefinition 'using System;using System.Diagnostics;using System.Runtime.InteropServices;public static class Forward{[DllImport(\"user32.dll\")]static extern bool AllowSetForegroundWindow(int p);[DllImport(\"user32.dll\")]static extern bool SetForegroundWindow(IntPtr h);[DllImport(\"user32.dll\")]static extern IntPtr GetForegroundWindow();public static void Allow(){AllowSetForegroundWindow(-1);}public static void Bring(DateTime since){foreach(Process p in Process.GetProcessesByName(\"CredentialUIBroker\")){try{if(p.StartTime<since)continue;IntPtr h=p.MainWindowHandle;if(h!=IntPtr.Zero&&GetForegroundWindow()!=h)SetForegroundWindow(h);}catch{}}}}'",
        "[Forward]::Allow();$since=[DateTime]::Now.AddSeconds(-1)",
        "$asTask=[System.WindowsRuntimeSystemExtensions].GetMethods()|?{$_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'}|Select-Object -First 1",
        "function Await($op,[Type]$t){$task=$asTask.MakeGenericMethod($t).Invoke($null,@($op));while(-not $task.Wait(300)){[Forward]::Bring($since)};$task.Result}",
        "$null=[Windows.Security.Credentials.KeyCredentialManager,Windows.Security.Credentials,ContentType=WindowsRuntime]",
        "$null=[Windows.Security.Cryptography.CryptographicBuffer,Windows.Security.Cryptography,ContentType=WindowsRuntime]",
        "$how=$args[0];$challenge=$args[1]",
        "$null=[Windows.Storage.Streams.IBuffer,Windows.Storage.Streams,ContentType=WindowsRuntime]",
        "$toArray=[System.Runtime.InteropServices.WindowsRuntime.WindowsRuntimeBufferExtensions].GetMethod('ToArray',[Type[]]@([Windows.Storage.Streams.IBuffer]))",
        // Both conversions, with nothing asked of the person: bytes to a Windows buffer and back. A test runs it.
        "if($how -eq 'selftest'){$wb=[Windows.Security.Cryptography.CryptographicBuffer]::CreateFromByteArray([Convert]::FromBase64String($challenge));'SIGNED '+[Convert]::ToBase64String($toArray.Invoke($null,@($wb)));exit 0}",
        "if($how -eq 'check'){'SUPPORTED '+(Await ([Windows.Security.Credentials.KeyCredentialManager]::IsSupportedAsync()) ([bool]));exit 0}",
        "if($how -eq 'create'){$r=Await ([Windows.Security.Credentials.KeyCredentialManager]::RequestCreateAsync('Mininotes',[Windows.Security.Credentials.KeyCredentialCreationOption]::ReplaceExisting)) ([Windows.Security.Credentials.KeyCredentialRetrievalResult])}",
        "else{$r=Await ([Windows.Security.Credentials.KeyCredentialManager]::OpenAsync('Mininotes')) ([Windows.Security.Credentials.KeyCredentialRetrievalResult])}",
        "if($r.Status -ne 'Success'){'STATUS '+$r.Status;exit 2}",
        // The challenge goes in, and the signature comes out, as .NET's own buffer: a Windows one made by
        // CryptographicBuffer is refused by RequestSignAsync from PowerShell ("cannot convert to IBuffer").
        "$buffer=[System.Runtime.InteropServices.WindowsRuntime.WindowsRuntimeBufferExtensions]::AsBuffer([Convert]::FromBase64String($challenge))",
        "$s=Await ($r.Credential.RequestSignAsync($buffer)) ([Windows.Security.Credentials.KeyCredentialOperationResult])",
        "if($s.Status -ne 'Success'){'STATUS '+$s.Status;exit 3}",
        // And the signature out: a buffer Windows made cannot be handed to ToArray by PowerShell's own binder
        // ("cannot convert System.__ComObject"), which is what left Hello half set up. Reflection casts it.
        "'SIGNED '+[Convert]::ToBase64String($toArray.Invoke($null,@($s.Result)))");

    static String run(String how,String challenge) throws Exception {
        // Windows PowerShell (5.1, in every Windows 10 and 11), not PowerShell 7: only it can call Windows' own APIs.
        String shell=Path.of(System.getenv().getOrDefault("SystemRoot","C:\\Windows"),"System32","WindowsPowerShell","v1.0","powershell.exe").toString();
        String encoded=Base64.getEncoder().encodeToString(("& {"+SCRIPT+"} '"+how+"' '"+challenge+"'").getBytes(StandardCharsets.UTF_16LE));
        Process p=new ProcessBuilder(shell,"-NoProfile","-NonInteractive","-WindowStyle","Hidden","-EncodedCommand",encoded).redirectErrorStream(true).start();
        p.getOutputStream().close();
        String out=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        if(!p.waitFor(3,TimeUnit.MINUTES)){p.destroyForcibly();throw new IOException("Windows Hello took too long.");}
        for(String line:out.split("\\R"))if(line.startsWith("SIGNED ")||line.startsWith("STATUS ")||line.startsWith("SUPPORTED "))return line;
        // What Windows said instead, in its own words: "could not be reached" alone hid a fault here. An error
        // from a hidden PowerShell comes wrapped in XML ("#< CLIXML"); the error lines are taken out of it.
        String first="";
        java.util.regex.Matcher error=java.util.regex.Pattern.compile("<S S=\"Error\">(.*?)</S>").matcher(out);
        StringBuilder said=new StringBuilder();while(error.find())said.append(error.group(1).replace("_x000D__x000A_"," ").replace("&lt;","<").replace("&gt;",">").replace("&amp;","&").replace("&quot;","\""));
        if(said.length()>0)first=said.toString().replaceAll("\\s+"," ").trim();
        else for(String line:out.split("\\R"))if(!line.isBlank()&&!line.startsWith("#< CLIXML")){first=line.trim();break;}
        throw new IOException("Windows Hello could not be reached"+(first.isEmpty()?".":": "+(first.length()>160?first.substring(0,160)+"…":first)));
    }
}
