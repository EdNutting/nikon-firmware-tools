package com.nikonhacker.dfr;

///*
// * Copyright (c) 2007, Kevin Schoedel. All rights reserved.
// *
// * Redistribution and use in source and binary forms, with or without
// * modification, are permitted provided that the following conditions
// * are met:
// *
// * - Redistributions of source code must retain the above copyright
// *   notice, this list of conditions and the following disclaimer.
// *
// * - Redistributions in binary form must reproduce the above copyright
// *   notice, this list of conditions and the following disclaimer in the
// * 	 documentation and/or other materials provided with the distribution.
// *
// * - Neither the name of Kevin Schoedel nor the names of contributors
// *   may be used to endorse or promote products derived from this software
// *   without specific prior written permission.
// *
// * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
// * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
// * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
// * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
// * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
// * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
// * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
// * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
// * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
// * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
// */

///*
// *  1.00  2007/11/05  kps   First release.
// *  1.01  2007/11/06  kps   Fix unsigned int types, option parsing;
// *                          added split output; other minor tweaks.
// *  1.02  2007/11/07  kps   Bug fixes; minimal data flow tracking.
// *  1.03  2007/11/15  kps   Fixed a stupid bug.
//
// Further modifications and port to C# by Simeon Pilgrim
// Further modifications and port to Java by Vicne
// */

import com.nikonhacker.Format;
import com.nikonhacker.emu.memory.FastMemory;
import com.nikonhacker.emu.memory.Memory;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.*;
import java.util.*;

public class Dfr
{
    public static Set<OutputOption> outputOptions = EnumSet.noneOf(OutputOption.class);

    final static String cmdname = "Dfr";
    final static String version = "1.03";
    private static final String DEFAULT_OPTIONS_FILE = "dfr.txt";

    String inputFileName;
    String outputFileName;

    boolean optLittleEndian = false;
    boolean optSplitPerMemoryRange = false;

    Memory memory = new FastMemory();

    FileWriter fileWriter;

    MemoryMap fileMap = new MemoryMap('i', "File map");
    MemoryMap memMap = new MemoryMap('m', "Memory map");
    MemoryMap rangeMap = new MemoryMap('d', "Selected ranges");

    String startTime = "";
    private Map<Integer, Symbol> symbols = new HashMap<Integer, Symbol>();

    static void usage()
    {
        String help =
                "-d range          disassemble only specified range\n"
                        + "-e address=name   (not implemented) define entry point symbol\n"
                        + "-f range=address  (not implemented) map range of input file to memory address\n"
                        + "-h                display this message\n"
                        + "-i range=offset   map range of memory to input file offset\n"
                        + "-l                (not implemented) little-endian input file\n"
                        + "-m range=type     describe memory range (use -m? to list types)\n"
                        + "-o filename       output file\n"
                        + "-r                separate output file for each memory range\n"
                        + "-s address=name   define symbol\n"
                        + "-t address        interrupt vector start, equivalent to -m address,0x400=DATA:V\n"
                        + "-v                verbose\n"
                        + "-w options        output options (use -w? to list options)\n"
                        + "-x file           read options from file\n"
                        + "Numbers are C-style (16 or 0x10) and can be followed by the K or M multipliers.\n"
                        + "A range is start-end or start,length.\n";

        log("Usage: " + cmdname + "[options] filename");
        log("Options:");
        log(help);
    }

    public static void main(String[] args) throws IOException, DisassemblyException, ParsingException {
        new Dfr().execute(args);
    }

    private void execute(String[] args) throws ParsingException, IOException, DisassemblyException {
        if (!new File(DEFAULT_OPTIONS_FILE).exists()) {
            System.err.println("Default options file " + DEFAULT_OPTIONS_FILE + " not found.");
        }
        else {
            readOptions(DEFAULT_OPTIONS_FILE);
        }
        processOptions(args);

        initialize();

        disassembleMemRanges(fileWriter);

        cleanup();
    }



    ///* Logging */

    void info(String s) throws IOException {
        fileWriter.write(s);

        if (outputOptions.contains(OutputOption.VERBOSE))
        {
            System.err.println(s);
        }
    }

    static void error(String s)
    {
        log("*** ERROR ***");
        log(s);
    }


    static void log(String s)
    {
        System.err.println(s);
    }



    ///* misc definitions */

    /**
     * Extend with Negative sign
     * @param i number of used bits in original number
     * @param x original number
     * @return
     */
    public static int extn(int i, int x) {
        int mask = (1 << i) - 1;
        return ~mask | x;
    }

    static int signExtendMask(int b, int x)
    {
        return ((-b) * ((b & x) != 0 ? 1 : 0));
    }

    /**
     * Interpret x as a signed value based on its last n bits, and extend that MSB
     * so that return represents the same number, but on 32 bits
     * @param n the number of bits to take into account
     * @param x the original number
     */
    public static int signExtend(int n, int x)
    {
        return (x | signExtendMask((1 << (n - 1)), x));
    }

    public static int NEG(int n, int x)
    {
        return (-signExtend((n), (x)));
    }

    static boolean IsNeg(int n, int x)
    {
        return ((x) & (1 << ((n) - 1))) != 0;
    }


    ///* output */

    void openOutput(int pc, boolean usePC, String ext) throws IOException {
        String outName = "";
        if (outputFileName == null) {
            outName = FilenameUtils.getBaseName(inputFileName);
        }
        if (usePC) {
            outName += "_" + Format.asHex(pc, 8);
        }
        
        if (fileWriter != null) {
            fileWriter.close();
        }

        fileWriter = new FileWriter(outName + "." + ext);
    }

    
    void writeHeader() throws IOException {
        writeHeader(fileWriter);
    }

    private void writeHeader(Writer writer) throws IOException {
        writer.write("DFR " + version + "\n");
        writer.write("  Date:   " + startTime + "\n");
        writer.write("  Input:  " + inputFileName + "\n");
        writer.write("  Output: " + (outputFileName == null ? "(default)" : outputFileName) + "\n");
        writer.write("\n");
    }


    int disassembleOneInstruction(Writer writer, CPUState cpuState, Range memRange, int memoryFileOffset, CodeStructure codeStructure) throws IOException {
        DisassembledInstruction disassembledInstruction = new DisassembledInstruction(memRange.start);
        disassembledInstruction.getNextInstruction(memory, cpuState.pc);
        if ((disassembledInstruction.opcode = OpCode.opCodeMap[disassembledInstruction.data[0]]) == null)
        {
            disassembledInstruction.opcode = OpCode.opData[DATA.SpecType_MD_WORD];
        }

        disassembledInstruction.decodeInstructionOperands(cpuState, memory);

        disassembledInstruction.formatOperandsAndComment(cpuState, true, outputOptions);
        
        if (codeStructure != null) {
            codeStructure.getInstructions().put(cpuState.pc, disassembledInstruction);
        }
        else {
            // No structure analysis, output right now
            printDisassembly(writer, disassembledInstruction, cpuState, memoryFileOffset);
        }

        return disassembledInstruction.n << 1;
    }


    int disassembleOneDataRecord(Writer writer, CPUState cpuState, Range memRange, int memoryFileOffset) throws IOException {

        int sizeInBytes = 0;

        for (int spec : memRange.data.spec)
        {
            DisassembledInstruction disassembledInstruction = new DisassembledInstruction(memRange.start);
            disassembledInstruction.getNextData(memory, cpuState.pc);
            disassembledInstruction.x = disassembledInstruction.data[0];
            disassembledInstruction.xBitWidth = 16;
            disassembledInstruction.opcode = OpCode.opData[spec];

            disassembledInstruction.decodeInstructionOperands(cpuState, memory);

            disassembledInstruction.formatOperandsAndComment(cpuState, true, outputOptions);

            sizeInBytes += disassembledInstruction.n << 1;

            printDisassembly(writer, disassembledInstruction, cpuState, memoryFileOffset);
        }

        return sizeInBytes;
    }

    /**
     *
     *
     *
     * @param writer
     * @param disassembledInstruction
     * @param cpuState
     * @param memoryFileOffset offset between memory and file (to print file position alongside memory address)
     * @throws IOException
     */
    private void printDisassembly(Writer writer, DisassembledInstruction disassembledInstruction, CPUState cpuState, int memoryFileOffset) throws IOException {
        writer.write(Format.asHex(cpuState.pc, 8) + " ");

        if (memoryFileOffset != 0) {
            writer.write("(" + Format.asHex(cpuState.pc - memoryFileOffset, 8) + ") ");
        }

        writer.write(disassembledInstruction.toString());
    }


    void disassembleMemoryRange(Writer writer, Range memRange, Range fileRange, CodeStructure codeStructure) throws IOException, DisassemblyException {
        int startPc = memRange.start;
        int end = memRange.end;

        if ((startPc & 1) != 0)
        {
            error("Odd start address 0x" + Format.asHex(startPc, 8));
            // start &= 0xffFFffFFffFFffFe;
            startPc --;
        }
        if ((end & 1) != 0)
        {
            end++;
        }


        CPUState cpuState = new CPUState(startPc);

        int memoryFileOffset = fileRange.start - fileRange.fileOffset;

        while (cpuState.pc < end)
        {
            int sizeInBytes;
            if (memRange.data.isCode()) {
                sizeInBytes = disassembleOneInstruction(writer, cpuState, memRange, memoryFileOffset, codeStructure);
            }
            else {
                sizeInBytes = disassembleOneDataRecord(writer, cpuState, memRange, memoryFileOffset);
            }

            if (sizeInBytes < 0)
            {
                if (sizeInBytes != -1)
                    error("input error: " + (-sizeInBytes - 1));
                fileRange.end = cpuState.pc;
                System.out.println("WARNING : setting pc to max...");
                cpuState.pc = -1;
                break;
            }
            cpuState.pc += sizeInBytes;
        }
    }

    void disassembleMemRanges(Writer writer) throws IOException, DisassemblyException {
        
        CodeStructure codeStructure = null;
        if (outputOptions.contains(OutputOption.STRUCTURE)) {
            codeStructure = new CodeStructure(memMap.ranges.first().start);
        }

        for (Range memRange : memMap.ranges) {
            // find file offset covering this memory location.
            Range matchingFileRange = null;
            for (Range fileRange : fileMap.ranges) {
                if (memRange.start >= fileRange.start && memRange.start <= fileRange.end) {
                    matchingFileRange = fileRange;
                    break;
                }
            }

            if (matchingFileRange != null) {
                info("Disassemble 0x" + Format.asHex(memRange.start, 8) + "-0x" + Format.asHex(memRange.end, 8)
                        + " (file 0x" + Format.asHex(matchingFileRange.fileOffset, 8)
                        + ") as " + memRange.data + "\n");
                info("\n");
                disassembleMemoryRange(writer, memRange, matchingFileRange, codeStructure);
                info("\n");
            }
        }

        if (codeStructure != null) {
            codeStructure.postProcess(symbols, memMap.ranges, memory);
            
            for (Range range : memMap.ranges) {
                if (range.data.isCode()) {
                    codeStructure.writeDisassembly(writer, range);
                }
                else {
                    //writeData(range);
                }
            }

            // print and output
            log(codeStructure.getInstructions().size() + " instructions");
            log(codeStructure.getLabels().size() + " labels");
            log(codeStructure.getFunctions().size() + " functions");
            log(codeStructure.getReturns().size() + " returns");
        }

    }


    ///* initialization */

    void initialize() throws IOException {
        startTime = new Date().toString();

        if (inputFileName == null)
        {
            log(cmdname + ": no input file\n");
            usage();
            System.exit(-1);
        }

        OpCode.initOpcodeMap(outputOptions);

        DisassembledInstruction.initFormatChars(outputOptions);

        CPUState.initRegisterLabels(outputOptions);

//        if (outOptions.fileMap || outOptions.memoryMap) {
        openOutput(0, false, /*outOptions.optSplitPerMemoryRange ? "map" :*/ "asm");
        writeHeader();
//        }

        File binaryFile = new File(inputFileName);

        memory.loadFile(binaryFile, fileMap.ranges);


        //    fixmap(&filemap, 0);
//            if (outOptions.fileMap)
//                dumpmap(&filemap);

        //    fixmap(&memmap, MEMTYPE_UNKNOWN);
        //    fillmap(&memmap, MEMTYPE_UNKNOWN);
        //    delmap(&memmap, MEMTYPE_NONE);
//        if (outOptions.memoryMap)
//            dumpmap(&memmap);

        //    fixmap(&rangemap, 1);
//            if (outOptions.fileMap || outOptions.memoryMap)
//                dumpmap(&rangemap);
    }

    void cleanup() throws IOException {
        fileWriter.close();
    }


    ///* options */


    /**
     * Processes options passed as a String array. E.g. {"infile.bin", "-t1", "-m", "0x00040000-0x00040947=CODE"}
     * @param args
     * @return
     * @throws ParsingException
     */
    boolean processOptions(String[] args) throws ParsingException {
        Character option;
        String argument;
        OptionHandler optionHandler = new OptionHandler(args);

        while ((option = optionHandler.getNextOption()) != null)
        {
            switch (option)
            {
                case 0:
                    // Not an option => Input file. Check we don't have one already
                    if (inputFileName != null)
                    {
                        log("too many input files");
                        usage();
                        return false;
                    }
                    inputFileName = optionHandler.getArgument();
                    break;


                case 'D':
                case 'd':
                    argument = optionHandler.getArgument();
                    if (argument == null || argument.length() == 0) {
                        log("option \"-" + option + "\" requires an argument");
                        return false;
                    }

                    Range range1 = OptionHandler.parseOffsetRange(option, argument);
                    range1.setFileOffset(1);
                    rangeMap.add(range1);
                    break;

                case 'E':
                case 'e':
                    argument = optionHandler.getArgument();
                    if (StringUtils.isBlank(argument)) {
                        log("option \"-" + option + "\" requires an argument");
                        return false;
                    }
                    System.err.println("-" + option + ": not implemented yet!\n");
                    System.exit(1);
                    break;

                case 'F':
                case 'f':
                    argument = optionHandler.getArgument();
                    if (StringUtils.isBlank(argument)) {
                        log("option \"-" + option + "\" requires an argument");
                        return false;
                    }
                    System.err.println("-" + option + ": not implemented yet!\n");
                    System.exit(1);
                    //        if (parseOffsetRange(opt, arg, &r, &start, &end, &map))
                    //            break;
                    //        insmap(&filemap, map, map + end - start, start);
                    break;

                case 'H':
                case 'h':
                case '?':
                    usage();
                    return false;

                case 'I':
                case 'i':
                    argument = optionHandler.getArgument();
                    if (StringUtils.isBlank(argument)) {
                        log("option \"-" + option + "\" requires an argument");
                        return false;
                    }

                    Range range = OptionHandler.parseOffsetRange(option, argument);
                    if (range == null)
                        break;

                    fileMap.add(range);
                    break;

                case 'L':
                case 'l':
                    System.err.println("-" + option + ": not implemented yet!\n");
                    System.exit(1);
                    optLittleEndian = true;
                    break;

                case 'M':
                case 'm':
                    argument = optionHandler.getArgument();
                    memMap.add(OptionHandler.parseTypeRange(option, argument));
                    break;

                case 'O':
                case 'o':
                    outputFileName = optionHandler.getArgument();
                    if (StringUtils.isBlank(outputFileName)) {
                        log("option '-" + option + "' requires an argument");
                        return false;
                    }
                    break;

                case 'R':
                case 'r':
                    optSplitPerMemoryRange = true;
                    break;

                case 'S':
                case 's':
                    argument = optionHandler.getArgument();
                    if (StringUtils.isBlank(argument)) {
                        log("option \"-" + option + "\" requires an argument");
                        return false;
                    }
                    OptionHandler.parseSymbol(symbols, argument);
                    break;

                case 'T':
                case 't':
                    argument = optionHandler.getArgument();
                    if (StringUtils.isBlank(argument)) {
                        log("option \"-" + option + "\" requires an argument");
                        return false;
                    }
                    memMap.add(OptionHandler.parseTypeRange(option, argument + "," + CodeStructure.INTERRUPT_VECTOR_LENGTH + "=DATA:V"));
                    break;

                case 'V':
                case 'v':
                    outputOptions.add(OutputOption.VERBOSE);
                    break;

                case 'W':
                case 'w':
                    argument = optionHandler.getArgument();
                    if (StringUtils.isBlank(argument)) {
                        log("option \"-" + option + "\" requires an argument");
                        return false;
                    }
                    if (!OutputOption.parseFlag(outputOptions, option, argument)) {
                        System.exit(1);
                    }
                    break;

                case 'X':
                case 'x':
                    argument = optionHandler.getArgument();
                    if (StringUtils.isBlank(argument)) {
                        log("option \"-" + option + "\" requires an argument");
                        return false;
                    }
                    try {
                        readOptions(argument);
                    } catch (IOException e) {
                        System.err.println("Cannot open given options file '" + argument + "'");
                        System.exit(1);
                    }
                    break;

                case 'Z':
                case 'z':
                    outputOptions.add(OutputOption.DEBUG);
                    break;

                default:
                    log("unknown option \"-" + option + "\"");
                    usage();
                    return false;
            }
        }

        return true;
    }


    private void readOptions(String filename) throws IOException, ParsingException {
        BufferedReader fp = new BufferedReader(new FileReader(filename));

        String buf;
        while ((buf = fp.readLine()) != null)
        {
            buf = buf.trim();
            if (buf.length() > 0 && buf.charAt(0) != '#')
            {
                if ((buf.charAt(0) == '-') && buf.length() > 2)
                {
                    // This is an option line
                    if (Character.isWhitespace(buf.charAt(2)))
                    {
                        String option = buf.substring(0, 2);
                        String params = buf.substring(2).trim();
                        if (StringUtils.isNotBlank(params))
                        {
                            processOptions(new String[]{option, params});
                            continue;
                        }
                    }
                }

                processOptions(new String[]{buf});
            }
        }
    }
}