/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package modes;

import GetCmdOpt.SimpleModeCmdLineParser;
import dataStructs.DivetOutputHandle;
import dataStructs.SamRecordMatcher;
import dataStructs.SplitOutputHandle;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import net.sf.samtools.SAMFileReader;
import net.sf.samtools.SAMFormatException;
import net.sf.samtools.SAMRecord;
import net.sf.samtools.SAMRecordIterator;
import workers.BamMetadataGeneration;
import workers.MrsFastRuntimeFactory;

/**
 *
 * @author bickhart
 */
public class PreprocessMode {
    private boolean checkRG = false;
    private final String outbase;
    private int samplimit = 1000;
    private int maxdist = 100000;
    private final String input;
    private int threads = 1;
    private final String reference;
    private final boolean debug;
    
    public PreprocessMode(SimpleModeCmdLineParser values){
        outbase = values.GetValue("output");
        input = values.GetValue("input");
        reference = values.GetValue("reference");
        
        // Optional values
        if(values.HasOpt("checkRG"))
            if(values.GetValue("checkRG").equals("true"))
                checkRG = true;
            
        if(values.HasOpt("threads"))
            threads = Integer.parseInt(values.GetValue("threads"));
        
        if(values.HasOpt("maxdist"))
            maxdist = Integer.parseInt(values.GetValue("maxdist"));
        
        if(values.HasOpt("samplimit"))
            samplimit = Integer.parseInt(values.GetValue("samplimit"));
        
        debug = values.HasOpt("debug");
    }
    
    public void run(){
        // Generate BAM file metadata class
        BamMetadataGeneration metadata = new BamMetadataGeneration(checkRG);
        metadata.ScanFile(input, samplimit);
        
        Map<String, Integer[]> values = metadata.getThresholds(maxdist);
        Map<String, DivetOutputHandle> divets = metadata.generateDivetOuts(outbase);
        Map<String, SplitOutputHandle> splits = metadata.generateSplitOuts(outbase);
        
        System.err.println("[PREPROCESS] Read input file and calculated sample thresholds.");
        metadata.getSampleIDs().stream().forEach((s) -> {
            System.err.println("Sample: " + s + " Avg Ins size: " + metadata.getSampleInsSize(s) +
                    " Stdev Ins size: " + metadata.getSampleInsStd(s));
        });
        
        // Run through the BAM file generating split and divet data
        SAMFileReader reader = new SAMFileReader(new File(input));
        reader.setValidationStringency(SAMFileReader.ValidationStringency.LENIENT);
        SamRecordMatcher worker = new SamRecordMatcher(samplimit, checkRG, outbase + "_tmp_", values, debug);
        SAMRecordIterator itr = reader.iterator();
        while(itr.hasNext()){
            SAMRecord s;
            try{
                s = itr.next();
            }catch(SAMFormatException ex){
                // this should ignore sam validation errors for crap reads
                System.err.println(ex.getMessage());
                continue;
            }
            worker.bufferedAdd(s);
        }
        itr.close();
        
        worker.convertToVariant(divets, splits);
        reader = new SAMFileReader(new File(input));
        worker.RetrieveMissingAnchors(splits, reader.iterator());
        
        System.err.println("[PREPROCESS] Generated initial split and divet data.");
        // Run MrsFAST on the split fastqs and generate bam files
        MrsFastRuntimeFactory mfact = new MrsFastRuntimeFactory(threads, metadata.getSamFileHeader());
        mfact.ProcessSplitFastqs(splits, reference, outbase);
        Map<String, String> bams = mfact.getBams();
        
        // Capture all output into a flatfile format and prepare to print it
        try(BufferedWriter out = Files.newBufferedWriter(Paths.get(outbase + ".flat"), Charset.defaultCharset())){
            for(String s : values.keySet()){
                out.write(bams.get(s) + "\t" + divets.get(s).getDivetFileStr() + "\t");
                out.write(splits.get(s).getAnchorFileStr() + "\t" + String.valueOf(metadata.getSampleInsSize(s)) + "\t");
                out.write(String.valueOf(metadata.getSampleInsStd(s)));
                out.newLine();                
            }
        }catch(IOException ex){
            ex.printStackTrace();
        }
        
        System.err.println("[PREPROCESS] Cleaning up temporary files...");
        splits.keySet().stream().forEach((s) -> {
            try{
                Files.deleteIfExists(Paths.get(splits.get(s).fq1File()));
            }catch(IOException ex){
                ex.printStackTrace();
            }
        });
        mfact.getSams().keySet().stream().forEach((s) -> {
            try{
                Files.deleteIfExists(Paths.get(s));
            }catch(IOException ex){
                ex.printStackTrace();
            }
        });
    }
}
