# Solving PDPTW problem based on Greedy Algorithm
This is the coursework of COMP4134 Advanced Topics in Machine Learning (2024-2025).

---

## How to use codes?

The final code file called `AADS.java` is a single Java source code file containing all code for this coursework. The file doesn't require any other files outside of the standard Java packages, which are always available. The file must compile and execute without warnings or errors using the command.

1) Compile:
   ```
   javac -encoding UTF-8 -sourcepath . AADS.java
   ```
2) Execute:
   ```
   java -Dfile.encoding=UTF-8 -XX:+UseSerialGC -Xss64m -Xms1920m -Xmx1920m AADS < Input.json > Output.txt
   ```

**Tips**: 
1) In the command of execution, the `Input.json` represents the input JSON file and the `Output.txt` represents the output file.
2) The input JSON file should be located in the same directory as the `AADS.java` file.
3) The program sends its output to standard output (by executing the above command, it will produce Output.txt in the same directory as `AADS.java` and `Input.json`, so no FileWriter is required)

---
