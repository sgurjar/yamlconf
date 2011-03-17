import java.io.FileReader;
import java.util.Iterator;

import com.sgurjar.AppConfig;

/** This is a test driver and sample usage of  AppConfig class. **/
public class T1 {
    public static void main(String[] args) throws Exception {
        String file=
            //"YAML-AppConfig-0.16/t/data/config.yaml"
            //"YAML-AppConfig-0.16/t/data/normal.yaml"
            "YAML-AppConfig-0.16/t/data/vars.yaml"
            //"YAML-AppConfig-0.16/t/data/scoping.yaml"
            ;

        AppConfig appconfig=new AppConfig(new FileReader(file));
        for(Iterator it=appconfig.config_keys(); it.hasNext();){
            String key=(String)it.next();
            try{
            System.out.println(key + "=" + appconfig.get(key));
            }catch(AssertionError e){System.err.println(e.toString());}
        }

        //System.out.println("eep="+appconfig.get("breezy"));
        //System.out.println(appconfig.config());
    }
}
