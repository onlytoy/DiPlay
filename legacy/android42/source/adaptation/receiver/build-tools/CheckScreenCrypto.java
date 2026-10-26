import java.util.Arrays;
import com.shilapi.xcertplay.airplay.AirPlayCrypto;
import local.airuize.receiver.LegacyScreenCrypto;

/** Independent BC ciphertext; native and absent-library cases run in separate JVMs. */
public final class CheckScreenCrypto {
    public static void main(String[] args) throws Exception {
        boolean expected=Boolean.parseBoolean(args[0]);
        if(LegacyScreenCrypto.INSTANCE.getAvailable()!=expected)throw new AssertionError("Wrong backend");
        byte[] key=new byte[32],nonce=new byte[12],aad=new byte[128];
        for(int i=0;i<key.length;i++)key[i]=(byte)(i*7);
        for(int i=0;i<aad.length;i++)aad[i]=(byte)i;
        for(int size:new int[]{0,1,15,16,17,1024,18000,65537,1024*1024}) {
            byte[] plain=new byte[size];for(int i=0;i<size;i++)plain[i]=(byte)(i*31);
            byte[] sealed=AirPlayCrypto.INSTANCE.chachaSeal(key,nonce,plain,aad);
            byte[] output=LegacyScreenCrypto.INSTANCE.open(key,nonce,sealed.clone(),aad);
            if(!Arrays.equals(output,plain))throw new AssertionError("Incorrect plaintext length="+size);
            for(int corrupt:new int[]{0,1,2,3}) {
                byte[] bad=sealed.clone(),badAad=aad.clone(),badNonce=nonce.clone(),badKey=key.clone();
                if(corrupt==0)bad[bad.length-1]^=1;
                if(corrupt==1)badAad[127]^=1;
                if(corrupt==2)badNonce[0]^=1;
                if(corrupt==3)badKey[0]^=1;
                byte[] before=bad.clone();boolean rejected=false;
                try {LegacyScreenCrypto.INSTANCE.open(badKey,badNonce,bad,badAad);}catch(Exception e){if(!(e instanceof org.bouncycastle.crypto.InvalidCipherTextException))throw new AssertionError(e);rejected=true;}
                if(!rejected || !Arrays.equals(before,bad))throw new AssertionError("Invalid frame accepted or mutated");
            }
            if(!Arrays.equals(LegacyScreenCrypto.INSTANCE.open(key,nonce,sealed.clone(),aad),plain))throw new AssertionError("Recovery failed");
        }
        if(expected && LegacyScreenCrypto.INSTANCE.getOpens().get()==0)throw new AssertionError("Native unused");
        if(!expected && LegacyScreenCrypto.INSTANCE.getFallbackOpens().get()==0)throw new AssertionError("Fallback unused");
        System.out.println("PASS: screen RFC8439 self-test, sizes 0..1MiB, ciphertext/tag/AAD/key/nonce rejection without mutation, recovery; native="+expected);
    }
}
