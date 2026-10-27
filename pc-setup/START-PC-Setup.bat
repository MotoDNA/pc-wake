@echo off
rem PC Wake - setup helper launcher (runs PC-setup.ps1 as administrator)
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process powershell -Verb RunAs -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','\"%~dp0PC-setup.ps1\"'"
