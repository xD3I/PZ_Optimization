package zombie;

import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

@XmlRootElement(name = "fileGuidTable")
public final class FileGuidTable {
   @XmlElement(name = "files")
   public final ArrayList<FileGuidPair> files = new ArrayList<>();
   @XmlTransient
   private final Map<String, String> guidToPath = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
   @XmlTransient
   private final Map<String, String> pathToGuid = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
   @XmlTransient
   private volatile Map<String, String> pzoptGuidMemo; // pzopt: lookupMemo, exact-string memo of guidToPath (made on first use: the constructor stays stock)
   @XmlTransient
   private volatile Map<String, String> pzoptPathMemo; // pzopt: lookupMemo, exact-string memo of pathToGuid

   public void setModID(String modID) {
      for (FileGuidPair pair : this.files) {
         pair.guid = modID + "-" + pair.guid;
      }
   }

   public void mergeFrom(FileGuidTable other) {
      this.files.addAll(other.files);
   }

   public void loaded() {
      this.pzoptGuidMemo = null; // pzopt: lookupMemo, the trees change
      this.pzoptPathMemo = null; // pzopt
      for (FileGuidPair pair : this.files) {
         this.guidToPath.put(pair.guid, pair.path);
         this.pathToGuid.put(pair.path, pair.guid);
      }
   }

   public void clear() {
      this.pzoptGuidMemo = null; // pzopt: lookupMemo, the trees change
      this.pzoptPathMemo = null; // pzopt
      this.files.clear();
      this.guidToPath.clear();
      this.pathToGuid.clear();
   }

   public String getFilePathFromGuid(String guid) {
      if (pzopt.LookupMemo.ON) { // pzopt: lookupMemo (f8-resid), an exact-string hash memo in front of the case-insensitive tree
         Map<String, String> m = this.pzoptGuidMemo; // pzopt
         if (m == null) { // pzopt
            this.pzoptGuidMemo = m = pzopt.LookupMemo.newStringMemo(); // pzopt: two threads racing here each make one; a lost memo costs a lookup
         } // pzopt
         return pzopt.LookupMemo.get(m, this.guidToPath, guid); // pzopt
      } // pzopt
      return this.guidToPath.get(guid);
   }

   public String getGuidFromFilePath(String path) {
      if (pzopt.LookupMemo.ON) { // pzopt: lookupMemo (f8-resid)
         Map<String, String> m = this.pzoptPathMemo; // pzopt
         if (m == null) { // pzopt
            this.pzoptPathMemo = m = pzopt.LookupMemo.newStringMemo(); // pzopt
         } // pzopt
         return pzopt.LookupMemo.get(m, this.pathToGuid, path); // pzopt
      } // pzopt
      return this.pathToGuid.get(path);
   }
}
