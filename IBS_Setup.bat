@echo off
chcp 65001 >nul
title IBS Config App - Setup Tool (v2)
color 0A

set ADB=%~dp0platform-tools\adb.exe
set APK=%~dp0app-release.apk
set PACKAGE=com.ibs.configapp
set COMPONENT=com.ibs.configapp/com.ibs.configapp.IbsDeviceAdminReceiver

:MENU
cls
echo ============================================================
echo   IBS CONFIG APP - SETUP TOOL (v2)
echo ============================================================
echo.
echo   USB cable se connect karein, YA Wireless Debugging use
echo   karein (option 6) agar USB kaam na kare.
echo.
echo   ZAROORI: Phone FRESH/FACTORY-RESET hona chahiye
echo   (koi Google account login na ho) is process ke liye.
echo.
echo ============================================================
echo.
echo   [1] Phone Connection Check Karein
echo   [2] IBS Config App Install Karein
echo   [3] Device Owner Set Karein
echo   [4] Poora Process (1+2+3 ek sath)
echo   [5] Device Owner Status Verify Karein (zaroori check)
echo   [6] Wireless Debugging Se Connect Karein (agar USB fail ho)
echo   [7] Exit
echo.
echo ============================================================
set /p CHOICE="Apna option number likhein aur Enter dabayein: "

if "%CHOICE%"=="1" goto CHECK
if "%CHOICE%"=="2" goto INSTALL
if "%CHOICE%"=="3" goto SETOWNER
if "%CHOICE%"=="4" goto FULLPROCESS
if "%CHOICE%"=="5" goto VERIFY
if "%CHOICE%"=="6" goto WIRELESS
if "%CHOICE%"=="7" goto END
goto MENU

:CHECK
cls
echo ============================================================
echo   PHONE CONNECTION CHECK
echo ============================================================
echo.
"%ADB%" devices
echo.
echo   Agar upar phone ki ID dikhi hai (device/unauthorized ke sath),
echo   to phone connect ho gaya hai.
echo.
echo   Agar phone pe "Allow USB debugging?" ka popup aaya hai,
echo   to us par ALLOW dabayein, phir dobara option [1] try karein.
echo.
echo   Agar "no devices found" ya "USB device not recognized" aaye,
echo   to Option [6] (Wireless Debugging) try karein.
echo.
pause
goto MENU

:INSTALL
cls
echo ============================================================
echo   INSTALLING IBS CONFIG APP
echo ============================================================
echo.
if not exist "%APK%" (
    echo   GALTI: app-release.apk file nahi mili!
    echo   Ye file isi folder mein honi chahiye jahan ye .bat hai.
    echo.
    pause
    goto MENU
)
echo   APK install ho rahi hai, thora wait karein...
echo.
"%ADB%" install -r "%APK%"
echo.
echo ============================================================
echo   Agar upar "Success" likha hai, install ho gayi hai.
echo ============================================================
pause
goto MENU

:SETOWNER
cls
echo ============================================================
echo   DEVICE OWNER SET KARNA
echo ============================================================
echo.
echo   ZAROORI: Ye sirf FRESH/FACTORY-RESET phone par kaam karega
echo   jisme koi Google account login na ho, aur IBS Config App
echo   pehli dafa install hui ho (koi purani install/admin state
echo   na ho).
echo.
set /p CONFIRM="Kya phone fresh/factory-reset hai aur app abhi install hui? (Y/N): "
if /i not "%CONFIRM%"=="Y" (
    echo.
    echo   Pehle phone ko factory reset karein aur app install
    echo   karein (Option 2), phir dobara try karein.
    pause
    goto MENU
)
echo.
echo   Device Owner set ho raha hai...
echo.
"%ADB%" shell dpm set-device-owner "%COMPONENT%"
echo.
echo ============================================================
echo   "Success" ya "Device owner set to" dikhne ka matlab command
echo   chal gayi - LEKIN ye guarantee nahi hai ke Device Owner
echo   asal mein set hua. AGLA STEP ZAROORI HAI:
echo.
echo   Option [5] (Verify) chala kar CONFIRM karein.
echo ============================================================
pause
goto MENU

:VERIFY
cls
echo ============================================================
echo   DEVICE OWNER STATUS VERIFY KARNA (ZAROORI)
echo ============================================================
echo.
echo   Ye check karega ke Device Owner ACTUALLY set hua hai ya
echo   sirf "Success" message dikha tha (kuch phones - khaas kar
echo   Oppo/ColorOS - par ye alag ho sakta hai).
echo.
"%ADB%" shell dumpsys device_policy | findstr /I "Device Owner Type"
echo.
echo ============================================================
echo   Agar upar "Device Owner Type: -1" dikhe = SET NAHI HUA.
echo     - App force-stop karein aur dobara try karein (Option 3)
echo     - Ya phone restart kar ke try karein
echo.
echo   Agar "Device Owner Type" ke aage koi number (0 ya zyada)
echo   dikhe (-1 ke ilawa) = SAHI SET HO GAYA.
echo ============================================================
pause
goto MENU

:WIRELESS
cls
echo ============================================================
echo   WIRELESS DEBUGGING SE CONNECT KARNA (USB fail hone par)
echo ============================================================
echo.
echo   PEHLE PHONE PAR YE KAREIN:
echo   1. Settings ^> About Phone ^> Build Number par 7 dafa tap
echo      karein (Developer Options unlock hongi)
echo   2. Settings ^> Developer Options ^> Wireless Debugging ON
echo      karein
echo   3. "Pair device with pairing code" par tap karein
echo   4. IP:PORT aur 6-digit CODE note kar lein
echo.
set /p PAIRIP="Pairing IP:PORT likhein (jaise 192.168.1.5:12345): "
"%ADB%" pair %PAIRIP%
echo.
echo   Ab phone ki Wireless Debugging screen par UPAR wala
echo   IP:PORT (pairing wale se ALAG) dekhein.
echo.
set /p CONNIP="Connect IP:PORT likhein: "
"%ADB%" connect %CONNIP%
echo.
"%ADB%" devices
echo.
echo   Agar "device" status dikhe (unauthorized nahi), to connect
echo   ho gaya hai - ab Option 2, 3, 5 use kar sakte hain.
echo.
echo   NOTE: Wireless Debugging session thori der mein khud
echo   disconnect ho sakti hai - agar aisa ho to Option 6 dobara
echo   chalayein (naya pairing code lagega).
echo.
pause
goto MENU

:FULLPROCESS
cls
echo ============================================================
echo   POORA PROCESS - STEP BY STEP
echo ============================================================
echo.
echo   STEP 1: Connection Check
echo   ------------------------
"%ADB%" devices
echo.
pause
echo.
echo   STEP 2: App Install
echo   -------------------
if not exist "%APK%" (
    echo   GALTI: app-release.apk file nahi mili!
    pause
    goto MENU
)
"%ADB%" install -r "%APK%"
echo.
pause
echo.
echo   STEP 3: Device Owner Set
echo   ------------------------
set /p CONFIRM2="Kya phone fresh/factory-reset hai? (Y/N): "
if /i not "%CONFIRM2%"=="Y" (
    echo   Pehle factory reset karein.
    pause
    goto MENU
)
"%ADB%" shell dpm set-device-owner "%COMPONENT%"
echo.
pause
echo.
echo   STEP 4: Verify (ZAROORI - skip na karein)
echo   ------------------------------------------
"%ADB%" shell dumpsys device_policy | findstr /I "Device Owner Type"
echo.
echo ============================================================
echo   "Device Owner Type: -1" = FAIL, dobara try karein.
echo   Koi aur number = SUCCESS, ab app khud khulegi phone par.
echo ============================================================
pause
goto MENU

:END
echo.
echo   Setup tool band ho raha hai. Allah Hafiz!
timeout /t 2 >nul
exit
