================================================
     IBS SYSTEM - DEVICE SETUP GUIDE
     Installment Business Solutions
================================================
IS FOLDER MEIN YE HONA CHAHIYE:
---------------------------------
✓ IBS_Setup.bat       (Setup script)
✓ app-release.apk     (Config App)
✓ platform-tools/     (ADB folder)
✓ README.txt          (Ye file)
PEHLI BAAR SETUP (SIRF EK BAAR):
----------------------------------
1. platform-tools ZIP download karein:
   https://developer.android.com/tools/releases/platform-tools
   
2. platform-tools folder ko is ZIP ke saath rakhein
CUSTOMER PHONE SETUP PROCESS:
-------------------------------
STEP 1 - PHONE FACTORY RESET KAREIN
   - Settings > General Management > Reset
   - Factory Data Reset karein
   - Confirm karein
STEP 2 - SETUP WIZARD MEIN (BOHOT ZAROORI!)
   - Language select karein (Urdu/English)
   - WiFi connect karein
   - "Sign in with Google" screen par SKIP karein
   - Baaki steps complete karein
STEP 3 - USB DEBUGGING ON KAREIN
   a) Settings kholein
   b) "About Phone" par jayein
   c) "Build Number" par 7 BAAR tap karein
      ("Developer ho gaye" message aayega)
   d) Wapas Settings mein jayein
   e) "Developer Options" kholein
   f) "USB Debugging" ON karein
STEP 4 - SCRIPT CHALAYEIN
   a) USB cable se phone laptop se connect karein
   b) Phone par "Allow USB Debugging?" popup aaye
      to "Allow" dabayein
   c) IBS_Setup.bat par DOUBLE CLICK karein
   d) Screen par instructions follow karein
   e) SUCCESS message aane tak wait karein
STEP 5 - PHONE RESTART KAREIN
   - Setup ke baad phone restart karein
   - Restart ke baad sab kuch ready!
COMMON PROBLEMS:
-----------------
PROBLEM: "Phone nahi mila" aata hai
SOLUTION: 
   - USB cable check karein
   - Phone mein Allow USB Debugging allow karein
   - Doosra USB port try karein
PROBLEM: "Device Owner set nahi hua"
SOLUTION:
   - Phone mein Google account logged in hai
   - Setup wizard mein Google SKIP karna zaroori hai
   - Phone dobara factory reset karein
PROBLEM: App install nahi hui
SOLUTION:
   - app-release.apk same folder mein hai?
   - Phone storage check karein
SUPPORT:
---------
WhatsApp: +92 321 2345152
Email: mrnasirhingoro@gmail.com
================================================