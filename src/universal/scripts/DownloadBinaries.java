/*+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
 +    Copyright (c) 2015 Aalto University.                                        +
 +                                                                                +
 +    Licensed under the 4-clause BSD (the "License");                            +
 +    you may not use this file except in compliance with the License.            +
 +    You may obtain a copy of the License at top most directory of project.      +
 +                                                                                +
 +    Unless required by applicable law or agreed to in writing, software         +
 +    distributed under the License is distributed on an "AS IS" BASIS,           +
 +    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.    +
 +    See the License for the specific language governing permissions and         +
 +    limitations under the License.                                              +
 +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++*/

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.GZIPInputStream;

public class DownloadBinaries {
    public static boolean isLoading = true;
    public static void loadingAnimation(String msg){
        isLoading = true;
        Thread t = new Thread(){
            String message = msg;
            String icon = "-\\|/";
            @Override
            public void run() {
                try{
                    for(int i = 0;isLoading;i = (i+1) % 4){
                       System.out.write(("\r" + (msg + icon.charAt(i))).getBytes());
                       Thread.sleep(100);
                    }
                } catch(IOException e) {
                  //do nothing
                } catch(InterruptedException e){
                  //do nothing
                }
            }
        };
        t.start();        
    }

    public static void main(String[] args){
        String inputPath = "";
        String jarLocation = "";
        //Charset charset = StandardCharsets.UTF_8;
        if (args.length > 1){
            inputPath = args[0];
            jarLocation = args[1];
        } else {
            System.err.println("give path to warp10 directory  AND jar location url as parameter");
            System.exit(1);
        }
        String tarGzFilename = "warp10.tar.gz";

        Path currentPath = Paths.get(inputPath).getParent().resolve(tarGzFilename);//.resolve("bin" + File.separator + tarGzFilename);
        Path warp10Location = Paths.get(inputPath);
        File dest = currentPath.toFile();
        String fileName = dest.toString();
        String tarFilename= fileName + ".tar";
        try {
            URL url = new URL(jarLocation);
            
            loadingAnimation("Downloading database files.");
            org.apache.commons.io.FileUtils.copyURLToFile(url, dest);
            isLoading = false;
            System.out.println("\rDownloading database files. ");
            
            FileInputStream inStream = new FileInputStream(dest);
            GZIPInputStream gzInStream = new GZIPInputStream(inStream);
            FileOutputStream outStream = new FileOutputStream(tarFilename);

            byte[] buf = new byte[1024];
            int len;
            
            while ((len = gzInStream.read(buf)) > 0) {
                outStream.write(buf, 0, len);
            }
            System.out.println("\rUncompressing GZIP file. ");

            gzInStream.close();
            outStream.close();

            TarArchiveInputStream tarInputStream = new TarArchiveInputStream(new FileInputStream(tarFilename));
            TarArchiveEntry entry = null;
            int offset = 0;
            FileOutputStream outputFile = null;

            while ((entry = tarInputStream.getNextTarEntry()) != null) {
                Path outputPath = warp10Location.resolve(entry.getName().substring(entry.getName().indexOf('/') + 1));
                if (outputPath.toString().endsWith(".jar")){
                    outputPath = outputPath.getParent().resolve("warp10.jar");
                }
                File outputDir = outputPath.toFile();
                if (!outputDir.getParentFile().exists()) {
                    outputDir.getParentFile().mkdirs();
                }

                if (entry.isDirectory()) {
                    outputDir.mkdirs();
                } else {
                    byte[] content = new byte[(int) entry.getSize()];
                    offset = 0;
                    tarInputStream.read(content, offset, content.length - offset);
                    outputFile = new FileOutputStream(outputDir);
                    IOUtils.write(content, outputFile);
                    outputFile.close();

                }
            }
            System.out.println("\rUnpacking Tar file. ");
           
            tarInputStream.close();
            File tarFile = new File(tarFilename);
            tarFile.delete();
        } catch (MalformedURLException ex) {
            System.err.println("Invalid URL for database binaries");
            ex.printStackTrace();
            isLoading = false;
            System.exit(1);
        } catch (FileNotFoundException ex) {
            System.err.println("File not Found: ");
            ex.printStackTrace();
            isLoading = false;
            System.exit(1);
        } catch (IOException ex) {
            System.err.println("Error while writing files: ");
            ex.printStackTrace();
            isLoading = false;
            System.exit(1);
        } finally {
            isLoading = false;
        }

    }
}