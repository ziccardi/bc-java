package org.bouncycastle.math;

import java.math.BigInteger;

import org.bouncycastle.crypto.Digest;
import org.bouncycastle.util.Arrays;

public abstract class Primes
{
    private static final BigInteger ONE = BigInteger.valueOf(1);
    private static final BigInteger TWO = BigInteger.valueOf(2);
    private static final BigInteger THREE = BigInteger.valueOf(3);

    /**
     * Used to return the output from the {@linkplain #generateSTRandomPrime(Digest) Shawe-Taylor Random_Prime Routine} 
     */
    public static class STOutput
    {
        private BigInteger prime;
        private byte[] primeSeed;
        private int primeGenCounter;

        private STOutput(BigInteger prime, byte[] primeSeed, int primeGenCounter)
        {
            this.prime = prime;
            this.primeSeed = primeSeed;
            this.primeGenCounter = primeGenCounter;
        }

        public BigInteger getPrime()
        {
            return prime;
        }

        public byte[] getPrimeSeed()
        {
            return primeSeed;
        }

        public int getPrimeGenCounter()
        {
            return primeGenCounter;
        }
    }

    /**
     * FIPS 186-4 C.6 Shawe-Taylor Random_Prime Routine
     * 
     * Construct a provable prime number using a hash function.
     * 
     * @param hash
     *            the {@link Digest} instance to use (as "Hash()"). Cannot be null.
     * @param length
     *            the length (in bits) of the prime to be generated. Must be >= 2.
     * @param inputSeed
     *            the seed to be used for the generation of the requested prime. Cannot be null or
     *            empty.
     * @returns an {@link STOutput} instance containing the requested prime.
     */
    public static STOutput generateSTRandomPrime(Digest hash, int length, byte[] inputSeed)
    {
        if (hash == null)
        {
            throw new IllegalArgumentException("'hash' cannot be null");
        }
        if (length < 2)
        {
            throw new IllegalArgumentException("'length' must be >= 2");
        }
        if (inputSeed == null || inputSeed.length == 0)
        {
            throw new IllegalArgumentException("'inputSeed' cannot be null or empty");
        }

        return implSTRandomPrime(hash, length, Arrays.clone(inputSeed));
    }

    private static STOutput implSTRandomPrime(Digest d, int length, byte[] primeSeed)
    {
        int dLen = d.getDigestSize();

        if (length < 33)
        {
            int primeGenCounter = 0;

            byte[] c0 = new byte[dLen];
            byte[] c1 = new byte[dLen];

            for (;;)
            {
                hash(d, primeSeed, c0, 0);
                inc(primeSeed, 1);

                hash(d, primeSeed, c1, 0);
                inc(primeSeed, 1);

                int c = extract32(c0) ^ extract32(c1);
                c &= (-1 >>> (32 - length));
                c |= (1 << (length - 1)) | 1;

                ++primeGenCounter;

                long c64 = c & 0xFFFFFFFFL;
                if (isPrime32(c64))
                {
                    return new STOutput(BigInteger.valueOf(c64), primeSeed, primeGenCounter);
                }

                if (primeGenCounter > (4 * length))
                {
                    throw new IllegalStateException("Too many iterations in Shawe-Taylor Random_Prime Routine");
                }
            }
        }

        STOutput rec = implSTRandomPrime(d, (length + 3)/2, primeSeed);

        BigInteger c0 = rec.getPrime();
        primeSeed = rec.getPrimeSeed();
        int primeGenCounter = rec.getPrimeGenCounter();

        int outlen = 8 * dLen;
        int iterations = (length - 1)/outlen;

        int oldCounter = primeGenCounter;

        BigInteger x = hashGen(d, primeSeed, iterations + 1);
        x = x.mod(ONE.shiftLeft(length - 1)).setBit(length - 1);

        BigInteger c0x2 = c0.shiftLeft(1);
        BigInteger tx2 = x.subtract(ONE).divide(c0x2).add(ONE).shiftLeft(1);
        int dt = 0;

        BigInteger c = tx2.multiply(c0).add(ONE);

        /*
         * TODO Since the candidate primes are generated by constant steps ('c0x2'),
         * sieving could be used here in place of the 'mightBePrime' approach.
         */
        for (;;)
        {
            if (c.bitLength() > length)
            {
                tx2 = ONE.shiftLeft(length - 1).subtract(ONE).divide(c0x2).add(ONE).shiftLeft(1);
                c = tx2.multiply(c0).add(ONE);
            }

            ++primeGenCounter;

            /*
             * This is an optimization of the original algorithm, using trial division to screen out
             * many non-primes quickly.
             * 
             * NOTE: 'primeSeed' is still incremented as if we performed the full check!
             */
            if (mightBePrime(c))
            {
                BigInteger a = hashGen(d, primeSeed, iterations + 1);
                a = a.mod(c.subtract(THREE)).add(TWO);

                tx2 = tx2.add(BigInteger.valueOf(dt));
                dt = 0;

                BigInteger z = a.modPow(tx2, c);

                if (c.gcd(z.subtract(ONE)).equals(ONE) && z.modPow(c0, c).equals(ONE))
                {
                    return new STOutput(c, primeSeed, primeGenCounter);
                }
            }
            else
            {
                inc(primeSeed, iterations + 1);
            }

            if (primeGenCounter >= ((4 * length) + oldCounter))
            {
                throw new IllegalStateException("Too many iterations in Shawe-Taylor Random_Prime Routine");
            }

            dt += 2;
            c = c.add(c0x2);
        }
    }

    private static int extract32(byte[] bs)
    {
        int result = 0;

        int count = Math.min(4, bs.length);
        for (int i = 0; i < count; ++i)
        {
            int b = bs[bs.length - (i + 1)] & 0xFF;
            result |= (b << (8 * i));
        }

        return result;
    }

    private static void hash(Digest d, byte[] input, byte[] output, int outPos)
    {
        d.update(input, 0, input.length);
        d.doFinal(output, outPos);
    }

    private static BigInteger hashGen(Digest d, byte[] seed, int count)
    {
        int dLen = d.getDigestSize();
        int pos = count * dLen;
        byte[] buf = new byte[pos];
        for (int i = 0; i < count; ++i)
        {
            pos -= dLen;
            hash(d, seed, buf, pos);
            inc(seed, 1);
        }
        return new BigInteger(1, buf);
    }

    private static void inc(byte[] seed, int c)
    {
        int pos = seed.length;
        while (c > 0 && --pos >= 0)
        {
            c += (seed[pos] & 0xFF);
            seed[pos] = (byte)c;
            c >>>= 8;
        }
    }

    private static boolean isPrime32(long x)
    {
        if (x >>> 32 != 0L)
        {
            throw new IllegalArgumentException("Size limit exceeded");
        }

        /*
         * Use wheel factorization with 2, 3, 5 to select trial divisors.
         */
        
        if (x <= 5L)
        {
            return x == 2L || x == 3L || x == 5L;
        }

        if ((x & 1L) == 0L || (x % 3L) == 0L || (x % 5L) == 0L)
        {
            return false;
        }

        long[] ds = new long[]{ 1L, 7L, 11L, 13L, 17L, 19L, 23L, 29L };
        long base = 0L;
        for (int pos = 1; ; pos = 0)
        {
            /*
             * Trial division by wheel-selected divisors
             */
            while (pos < ds.length)
            {
                long d = base + ds[pos];
                if (x % d == 0L)
                {
                    return x < 30L;
                }
                ++pos;
            }

            base += 30L;

            if (base * base >= x)
            {
                return true;
            }
        }
    }

    private static boolean mightBePrime(BigInteger x)
    {
        /*
         * Bundle trial divisors into ~32-bit moduli then use fast tests on the ~32-bit remainders.
         */
        int m = 2 * 3 * 5 * 7 * 11 * 13 * 17 * 19 * 23;
        int r = x.mod(BigInteger.valueOf(m)).intValue();
        if ((r & 1) != 0 && (r % 3) != 0 && (r % 5) != 0 && (r % 7) != 0 && (r % 11) != 0
            && (r % 13) != 0 && (r % 17) != 0 && (r % 19) != 0 && (r % 23) != 0)
        {
            m = 29 * 31 * 37 * 41 * 43;
            r = x.mod(BigInteger.valueOf(m)).intValue();
            if ((r % 29) != 0 && (r % 31) != 0 && (r % 37) != 0 && (r % 41) != 0 && (r % 43) != 0)
            {
                m = 47 * 53 * 59 * 61 * 67;
                r = x.mod(BigInteger.valueOf(m)).intValue();
                if ((r % 47) != 0 && (r % 53) != 0 && (r % 59) != 0 && (r % 61) != 0 && (r % 67) != 0)
                {
                    m = 71 * 73 * 79 * 83;
                    r = x.mod(BigInteger.valueOf(m)).intValue();
                    if ((r % 71) != 0 && (r % 73) != 0 && (r % 79) != 0 && (r % 83) != 0)
                    {
                        m = 89 * 97 * 101 * 103;
                        r = x.mod(BigInteger.valueOf(m)).intValue();
                        if ((r % 89) != 0 && (r % 97) != 0 && (r % 101) != 0 && (r % 103) != 0)
                        {
                            m = 107 * 109 * 113 * 127;
                            r = x.mod(BigInteger.valueOf(m)).intValue();
                            if ((r % 107) != 0 && (r % 109) != 0 && (r % 113) != 0 && (r % 127) != 0)
                            {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }
}