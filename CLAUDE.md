# Project Overview
Fabric 26.1.2 "Extended World Gen" mod in Java 25.
Yarn is now deleted, so don't use it. There is client and main folder.

# Decompiled Code
Important: if you need knowledge, search in the directory: 
- "\<mod_name>\decompiled" this contains decompiled Minecraft source code.
- net (e.g asset_editor\decompiled\net\minecraft\SharedConstants.java)
- com
- biome_presets_datapack (an example datapack)

# Global Rules:
- Gradle is accessible.
- No redundancy.
- No function/variable with a single line/reference. Except Getter/Setter...
- Avoid over engineering.
- No support of Legacy/Deprecated
- A class should have a signle dominant responsibility.
- Avoid dirty code / temporary code.
- It's better to tell me what you have in mind before doing it.
- Must use Translation Key instead Literal.
- Don't just write code that fixes a problem immediately, think long term and consider all possible future scenarios.
- Don't lie, prefer to tell the truth even when it's negative, don't please me just to please me, we must work factually.
- Try to criticize my choices which can sometimes go in the wrong direction.
- Don't just create full static files all the time; it's useless, unreadable, and counterproductive.
- Prioritize the OOP approach. Don't make everything in a static class. Use a correct Pattern. (Static is good but not for everything)
- Avoid unchecked, UNCHECKED_CAST find good architectural solutions that avoid them as much as possible.