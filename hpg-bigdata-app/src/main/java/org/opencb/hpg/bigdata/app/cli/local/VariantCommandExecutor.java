/*
 * Copyright 2015 OpenCB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opencb.hpg.bigdata.app.cli.local;

import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.opencb.biodata.models.variant.avro.VariantAvro;
import org.opencb.biodata.models.variant.avro.VariantFileMetadata;
import org.opencb.biodata.models.variant.avro.VariantFileMetadata.Builder;
import org.opencb.commons.io.DataReader;
import org.opencb.commons.run.ParallelTaskRunner;
import org.opencb.commons.utils.FileUtils;
import org.opencb.hpg.bigdata.app.cli.CommandExecutor;
import org.opencb.hpg.bigdata.core.converters.FullVcfCodec;
import org.opencb.hpg.bigdata.core.converters.variation.VariantAvroEncoderTask;
import org.opencb.hpg.bigdata.core.io.VcfBlockIterator;
import org.opencb.hpg.bigdata.core.io.avro.AvroFileWriter;

/**
 * Created by imedina on 25/06/15.
 */
public class VariantCommandExecutor extends CommandExecutor {

    private LocalCliOptionsParser.VariantCommandOptions variantCommandOptions;

    public VariantCommandExecutor(LocalCliOptionsParser.VariantCommandOptions variantCommandOptions) {
//      super(variantCommandOptions.c, fastqCommandOptions.verbose, fastqCommandOptions.conf);
        this.variantCommandOptions = variantCommandOptions;
    }


    @Override
    public void execute() throws Exception {
        String subCommandString = variantCommandOptions.getParsedSubCommand();
        switch (subCommandString) {
            case "convert":
                init(variantCommandOptions.convertVariantCommandOptions.commonOptions.logLevel,
                        variantCommandOptions.convertVariantCommandOptions.commonOptions.verbose,
                        variantCommandOptions.convertVariantCommandOptions.commonOptions.conf);
                convert();
                break;
            default:
                break;
        }
    }

    private void convert() throws Exception {
        String input = variantCommandOptions.convertVariantCommandOptions.input;
        String output = variantCommandOptions.convertVariantCommandOptions.output;

        // Checking input file
        FileUtils.checkFile(Paths.get(input));

        // Two options available: toAvro and fromAvro
        if (variantCommandOptions.convertVariantCommandOptions.toAvro) {
            String compression = variantCommandOptions.convertVariantCommandOptions.compression;

            // Creating reader
            VcfBlockIterator iterator = (StringUtils.equals("-", input))
                    ? new VcfBlockIterator(new BufferedInputStream(System.in), new FullVcfCodec())
                    : new VcfBlockIterator(Paths.get(input).toFile(), new FullVcfCodec());

            DataReader<CharBuffer> vcfDataReader = new DataReader<CharBuffer>() {
                @Override
                public List<CharBuffer> read(int size) {
                    return (iterator.hasNext() ? iterator.next(size) : Collections.<CharBuffer>emptyList());
                }

                @Override
                public boolean close() {
                    try {
                        iterator.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                        return false;
                    }
                    return true;
                }
            };

            // Creating file writer. If 'output' parameter is passed and it is different from
            // STDOUT then a file is created if parent folder exist, otherwise STDOUT is used.
            boolean isFile = false;
            OutputStream os;
            if (output != null && !output.isEmpty() && !output.equalsIgnoreCase("STDOUT")) {
                Path parent = Paths.get(output).toAbsolutePath().getParent();
                if (parent != null) { // null if output is a file in the current directory
                    FileUtils.checkDirectory(parent, true); // Throws exception, if does not exist
                }
                os = new FileOutputStream(output);
                isFile = true;
            } else {
                os = System.out;
            }
            AvroFileWriter<VariantAvro> avroFileWriter = new AvroFileWriter<>(VariantAvro.getClassSchema(), compression, os);

            // main loop
            int numTasks = Math.max(variantCommandOptions.convertVariantCommandOptions.numThtreads, 1);
            int batchSize = 1024 * 1024;  //Batch size in bytes
            int capacity = numTasks + 1;
//            VariantConverterContext variantConverterContext = new VariantConverterContext();
            ParallelTaskRunner.Config config = new ParallelTaskRunner.Config(numTasks, batchSize, capacity, false);
            ParallelTaskRunner<CharBuffer, ByteBuffer> runner =
                    new ParallelTaskRunner<>(
                            vcfDataReader,
                            () -> new VariantAvroEncoderTask(iterator.getHeader(), iterator.getVersion()),
                            avroFileWriter, config);
            long start = System.currentTimeMillis();
            runner.run();

            if (isFile) {
                String metaFile = output + ".meta";
                logger.info("Write metadata into " + metaFile);
                try (FileOutputStream out = new FileOutputStream(metaFile)) {
                    writeStats(new AvroFileWriter<>(VariantFileMetadata.getClassSchema(), compression, out), output);
                }
            }
            logger.debug("Time " + (System.currentTimeMillis() - start) / 1000.0 + "s");

        } else {
            if (variantCommandOptions.convertVariantCommandOptions.fromAvro) {
                logger.info("NOT IMPLEMENTED YET");
            }
        }
    }


    private void writeStats(AvroFileWriter<VariantFileMetadata> aw, String file) throws IOException {
        try {
            aw.open();
            Builder builder = VariantFileMetadata.newBuilder();
            builder
                .setStudyId(file)
                .setFileId(file);
            Map<String, String> meta = new HashMap<>();
            meta.put("FILTER_DEFAULT", "PASS");
            meta.put("QUAL_DEFAULT", StringUtils.EMPTY);
            meta.put("INFO_DEFAULT", "END,BLOCKAVG_min30p3a");
            meta.put("FORMAT_DEFAULT", "GT:GQX:DP:DPF");
            builder.setMetadata(meta);
            aw.writeDatum(builder.build());
        } finally {
            try {
                aw.close();
            } catch (Exception e) {
               e.printStackTrace();
            }
        }
    }

}
