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
   d) Agar ek se zyada phone/device connect hain, pehle
      Option [8] se target device select karein
   e) Option [4] (Poora Process) choose karein
   f) Screen par instructions follow karein
   g) SUCCESS message aane tak wait karein
STEP 5 - PHONE RESTART KAREIN
   - Setup ke baad phone restart karein
   - Restart ke baad sab kuch ready!
USB KAAM NA KARE TO - WIRELESS DEBUGGING:
-------------------------------------------
Agar USB cable se phone connect nahi ho raha, Option [6]
(Wireless Debugging) use karein:
   a) Phone aur laptop dono SAME WiFi par hone chahiye
   b) Settings > Developer Options > Wireless Debugging ON
   c) "Pair device with pairing code" par tap karein
   d) Script mein Option [6] choose karein
   e) Pehle wala IP:PORT aur 6-digit CODE daalein (pairing)
   f) Phone ki Wireless Debugging screen par UPAR wala
      (dusra, ALAG) IP:PORT dekhein aur wo daalein (connect)
   g) Script khud verify kar dega ke connect hua ya nahi
NOTE: Wireless session khud disconnect ho sakta hai - agar
aisa ho to Option [6] dobara chalayein (naya pairing code
lagega, IP:PORT bhi badal sakta hai).
EK SE ZYADA DEVICE CONNECTED HON TO (OPTION 8):
--------------------------------------------------
Agar USB aur Wireless dono se, ya ek se zyada phone connect
hain, Option [8] (Target Device Select) chalayein:
   - Script saare connected devices ki list dikhayega
   - Number se apna target phone choose karein
   - Uske baad Options 1-5 sirf usi phone par chalenge
   - Agar sirf EK device connected ho, script khud select
     kar lega, kuch karna nahi padega
COMMON PROBLEMS:
-----------------
PROBLEM: "Phone nahi mila" aata hai
SOLUTION: 
   - USB cable check karein
   - Phone mein Allow USB Debugging allow karein
   - Doosra USB port try karein
   - Ya Option [6] (Wireless Debugging) try karein
PROBLEM: "more than one device/emulator" error aata hai
SOLUTION:
   - Pehle Option [8] se target device select karein
   - Phir dobara Option chalayein jo fail hua tha
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
