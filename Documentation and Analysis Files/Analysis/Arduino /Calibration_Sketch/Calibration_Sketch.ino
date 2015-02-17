// AD5933 library implementation via Arduino serial monitor by Adetunji Dahunsi <tunjid.com>
// Updates should (hopefully) always be available at https://github.com/WuMRC

// This sketch prints out constituents for a MATLAB Map object "impedanceMap".
// Values of [f,r1,r2,c,key] are mapped to [z,r,x].

#include "Wire.h"
#include "Math.h"
#include "AD5933.h" //Library for AD5933 functions (must be installed)
#include "AD5258.h" //Library for AD5933 functions (must be installed)


#define TWI_FREQ 400000L      // Set TWI/I2C Frequency to 400MHz.

#define cycles_base 15       // Cycles to ignore before a measurement is taken. Max is 511.

#define cycles_multiplier 1    // Multiple for cycles_base. Can be 1, 2, or 4.

#define cal_resistance 461  // Calibration resistance for the gain factor. 

#define cal_samples 10         // Number of measurements to take of the calibration resistance.

#define nOfLevels 1 // 10 levels, with 3 factors. Frequency has 99 levels though.

#define fIncrements 3

#define nOfSamples 1

#define indicator_LED 7

// Define bit clearing and setting variables

#ifndef cbi
#define cbi(sfr, bit) (_SFR_BYTE(sfr) &= ~_BV(bit))
#endif
#ifndef sbi
#define sbi(sfr, bit) (_SFR_BYTE(sfr) |= _BV(bit))
#endif

// ================================================================
// Dynamic variables
// ================================================================

int ctrReg = 0; // Initialize control register variable.

uint8_t currentStep = 0; // Used to loop frequency sweeps.

double startFreqHz = 2000; // AC Start frequency (Hz).

double stepSizeHz = 1000; // AC frequency step size between consecutive values (Hz).

double systemPhaseShift = 0;       // Initialize system phase shift value.

double Z_Value = 0;          // Initialize impedance magnitude.

double rComp = 0;            // Initialize real component value.

double xComp = 0;            // Initialize imaginary component value.

double phaseAngle = 0;       // Initialize phase angle value.

double temp = 0; // Used to update AD5933's temperature.

double rw1 = 0; // Rheostat 1's wiper resistance

double rw2 = 0; // Rheostat 2's wiper resistance

double GF_Array[fIncrements + 1]; // gain factor array.

double PS_Array[fIncrements + 1]; // phase shift array.

double cArray[nOfLevels]; // capacitor values. 

double r1Array[nOfLevels]; // r1 values. 

double r2Array[nOfLevels]; // r2 values.

AD5258 r1; // rheostat r1

AD5258 r2; // rheostat r2

void setup() {

  Wire.begin(); // Start Arduino I2C library
  Serial.begin(38400);

  Serial.println();
  Serial.println();
  Serial.println("Starting...");

  pinMode(indicator_LED, OUTPUT);

  cbi(TWSR, TWPS0);
  cbi(TWSR, TWPS1); // Clear bits in port

  AD5933.setExtClock(false);
  AD5933.resetAD5933();
  AD5933.setRange(RANGE_4);
  AD5933.setStartFreq(startFreqHz);
  AD5933.setIncrement(stepSizeHz);
  AD5933.setNumofIncrement(fIncrements);
  AD5933.setSettlingCycles(cycles_base, cycles_multiplier);
  temp = AD5933.getTemperature();
  AD5933.setVolPGA(0, 1);
  AD5933.getGainFactorS_Set(cal_resistance, cal_samples, GF_Array, PS_Array); 

  // ctrReg = AD5933.getByte(0x80);

  Serial.println();

  for(int i = 0; i <= fIncrements; i++) { // print and set CR filter array.

    if(i == 0) {
      ctrReg = AD5933.getByte(0x80);
      AD5933.setCtrMode(STAND_BY, ctrReg);
      AD5933.setCtrMode(INIT_START_FREQ, ctrReg);
      AD5933.setCtrMode(START_FREQ_SWEEP, ctrReg);
      AD5933.getComplex(GF_Array[i], PS_Array[i], Z_Value, phaseAngle);
    }

    else if(i > 0 &&  i < fIncrements) {
      AD5933.getComplex(GF_Array[i], PS_Array[i], Z_Value, phaseAngle);
      AD5933.setCtrMode(INCR_FREQ, ctrReg);
    }

    else if(i = fIncrements) {
      AD5933.getComplex(GF_Array[i], PS_Array[i], Z_Value, phaseAngle);
      AD5933.setCtrMode(POWER_DOWN, ctrReg);
    }

    Serial.print("Frequency: ");
    Serial.print("\t");
    Serial.print(startFreqHz + (stepSizeHz * i));
    Serial.print("\t");        
    Serial.print("Gainfactor term: ");
    Serial.print(i);
    Serial.print("\t");
    Serial.print(GF_Array[i]);
    Serial.print("\t");
    Serial.print("SystemPS term: ");
    Serial.print(i);
    Serial.print("\t");
    Serial.print(PS_Array[i], 4);
    Serial.print("\t");        
    Serial.print("Z_Value: ");
    Serial.print(i);
    Serial.print("\t");
    Serial.print(Z_Value);        
    Serial.println(); 
  }  

  r1.begin(1);
  r2.begin(2);

  Serial.print("Start freq: ");
  Serial.print(startFreqHz);
  Serial.println();
}

void loop() {

  for(int i = 0; i < nOfLevels; i++) { // Capacitor loop

    Serial.println("Insert capacitor, and enter any key to continue. (Press only one key and enter)");
    digitalWrite(indicator_LED, LOW); 
    
    while (Serial.available() < 1) { // Wait for user to swap capacitors befor triggering
    } // End capacitor while

    Serial.read();
    digitalWrite(indicator_LED, HIGH);  // Indicates program is running.

    for(int j = 0; j < nOfLevels; j++) {  // r2 loop
      // Serial.println("r2 loop");
      r2.writeRDAC(j+1);

      for(int k = 0; k < nOfLevels; k++) {  // r1 loop
        // Serial.println("r1 loop");
        r1.writeRDAC(k+1);

        for(int currentStep = 0; currentStep <= fIncrements; currentStep++) { // frequency loop
          // Serial.print("currentStep: ");
          // Serial.println(currentStep);
          if(currentStep == 0) {
            AD5933.setCtrMode(STAND_BY, ctrReg);
            AD5933.setCtrMode(INIT_START_FREQ, ctrReg);
            AD5933.setCtrMode(START_FREQ_SWEEP, ctrReg);
          }

          else if(currentStep > 0 &&  currentStep < fIncrements) {
            AD5933.setCtrMode(INCR_FREQ, ctrReg);
          }

          else if(currentStep == fIncrements) {
            AD5933.setCtrMode(POWER_DOWN, ctrReg);
          }

          for(int m = 0; m < nOfSamples; m++) {  // number of samples loop
            // Serial.println("number of samples loop");
            AD5933.setCtrMode(REPEAT_FREQ); // Repeat measurement
            AD5933.getComplex(GF_Array[currentStep], PS_Array[currentStep], Z_Value, phaseAngle);

            // Print

            Serial.print(startFreqHz + (stepSizeHz * currentStep));
            Serial.print("\t");
            Serial.print(Z_Value, 4);
            Serial.print("\t"); 
            Serial.print(phaseAngle, 4);
            Serial.println();

          } // end number of samples loop
        } // end frequency loop
      } // end r1 loop
    } // end r2 loop
  } // End capacitor loop
} // End main loop

String generateMapKey(int fIndex, int r1Index, int r2Index, int cIndex, int level) {
  String key = "";
  key += "c";
  key += cIndex;
  key += level;
  key += "r";
  key += r1Index;
  key += level;
  key += "r";
  key += r2Index;
  key += level;
  key += "f";
  key += fIndex;
  key += level; 

  return key;
}

























