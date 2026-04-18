package estuadiantes.is.escuealing.edu.co;

import org.eclipse.jgit.api.Git;
import java.io.File;

public class GitCloner {
  public static File cloneOrUpdate(String repoUrl, String targetDir) throws Exception {
    File dir = new File(targetDir);
    if (dir.exists()) {
      try (Git git = Git.open(dir)) {
        git.pull().call();
        System.out.println("Hiciste pull en: " + dir.getAbsolutePath());
        return dir;
      } catch (Exception e) {
        // si falla, limpiamos y clonamos de nuevo
        deleteRecursively(dir);
      }
    }
    try (Git git = Git.cloneRepository()
        .setURI(repoUrl)
        .setDirectory(dir)
        .call()) {
      System.out.println("Repo clonado en: " + dir.getAbsolutePath());
      return dir;
    }
  }

  private static void deleteRecursively(File f) {
    if (!f.exists()) return;
    if (f.isDirectory()) {
      File[] children = f.listFiles();
      if (children != null) for (File c : children) deleteRecursively(c);
    }
    f.delete();
  }
}
