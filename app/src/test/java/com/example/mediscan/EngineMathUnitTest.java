package com.example.mediscan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.example.mediscan.engine.ColorProcessingProcessor;
import com.example.mediscan.engine.FundamentalsProcessor;
import com.example.mediscan.engine.RestorationProcessor;

import org.junit.Test;

public class EngineMathUnitTest {

    @Test
    public void testBitDepthQuantization() {
        int[] input = new int[]{85, 170};
        int[] output = new int[2];
        // Quantize to 1 bit (threshold at 128)
        FundamentalsProcessor.applyBitDepthQuantization(input, output, 1);
        int val0 = output[0];
        int val1 = output[1];
        assertEquals(0, val0);
        assertEquals(255, val1);
    }

    @Test
    public void testArithmeticMeanRestoration() {
        int[] input = new int[]{
                16, 32, 48,
                64, 80, 96,
                112, 128, 144
        };
        int[] output = new int[9];
        RestorationProcessor.applyArithmeticMeanFilter(input, output, 3, 3, 1);
        int centerVal = output[4];
        // Average of (16 + 32 + ... + 144) / 9 = 720 / 9 = 80
        assertEquals(80, centerVal);
    }

    @Test
    public void testContraharmonicMeanFilterEliminatesPepperNoise() {
        // Image with background of 120 and a pepper noise impulse (0) in center
        int[] input = new int[]{
                120, 120, 120,
                120, 0,   120,
                120, 120, 120
        };
        int[] output = new int[9];
        // Gonzalez & Woods 5.3.1: Q > 0 eliminates pepper noise
        RestorationProcessor.applyContraharmonicMeanFilter(input, output, 3, 3, 1, 1.5f);
        int restoredCenter = output[4];
        // Pepper noise (0) should be raised close to 120, completely eliminated
        assertTrue("Pepper noise must be eliminated by Q > 0", restoredCenter > 100);
    }

    @Test
    public void testContraharmonicMeanFilterEliminatesSaltNoise() {
        // Image with background of 60 and a salt noise impulse (255) in center
        int[] input = new int[]{
                60, 60,  60,
                60, 255, 60,
                60, 60,  60
        };
        int[] output = new int[9];
        // Gonzalez & Woods 5.3.1: Q < 0 eliminates salt noise
        RestorationProcessor.applyContraharmonicMeanFilter(input, output, 3, 3, 1, -1.5f);
        int restoredCenter = output[4];
        // Salt noise (255) should be reduced close to 60, completely eliminated
        assertTrue("Salt noise must be eliminated by Q < 0", restoredCenter < 80);
    }

    @Test
    public void testHarmonicMeanFilterEliminatesSaltNoise() {
        // Image with background of 100 and salt noise impulse (255)
        int[] input = new int[]{
                100, 100, 100,
                100, 255, 100,
                100, 100, 100
        };
        int[] output = new int[9];
        // Gonzalez & Woods 5.3.1: Harmonic mean eliminates salt noise
        RestorationProcessor.applyHarmonicMeanFilter(input, output, 3, 3, 1);
        int restoredCenter = output[4];
        // Harmonic mean suppresses high-value impulses because 1/255 is negligible
        assertTrue("Harmonic mean must suppress salt noise", restoredCenter < 120);
    }

    @Test
    public void testPseudocolorMapping() {
        int[] input = new int[]{0, 255};
        int[] output = new int[2];
        ColorProcessingProcessor.applyPseudocolor(input, output, ColorProcessingProcessor.ColormapType.RAINBOW_JET);
        int cold = output[0];
        int hot = output[1];
        // Blue component should dominate cold, Red component should dominate hot
        assertTrue("Cold end must be blue", (cold & 0xFF) > 100);
        assertTrue("Hot end must be red", ((hot >> 16) & 0xFF) > 100);
    }

    @Test
    public void testHsiExtraction() {
        // Pure red pixel: 0xFFFF0000
        int[] input = new int[]{0xFFFF0000};
        int[] outputHue = new int[1];
        int[] outputSat = new int[1];
        int[] outputInt = new int[1];

        ColorProcessingProcessor.extractHsiComponent(input, outputHue, 0); // Hue
        ColorProcessingProcessor.extractHsiComponent(input, outputSat, 1); // Saturation
        ColorProcessingProcessor.extractHsiComponent(input, outputInt, 2); // Intensity

        // Pure red: Hue is 0 deg (0), Saturation is 100% (255), Intensity is 255/3 = 85
        assertEquals(0, outputHue[0]);
        assertEquals(255, outputSat[0]);
        assertEquals(85, outputInt[0]);
    }
}
