package net.md_5.specialsource.mavenplugin;

import net.md_5.specialsource.Jar;
import net.md_5.specialsource.JarMapping;
import net.md_5.specialsource.JarRemapper;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.util.IOUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Create a remapped version of the main project artifact
 *
 * Based on maven-shade-plugin
 */
@Mojo(name = "remap", defaultPhase = LifecyclePhase.PACKAGE)
public class RemapMojo extends AbstractMojo {

    /**
     * The current Maven project.
     */
    @Component
    private MavenProject project;

    @Component
    private MavenProjectHelper projectHelper;

    /**
     * The destination directory for the shaded artifact.
     */
    @Parameter( defaultValue = "${project.build.directory}" )
    private File outputDirectory;

    /**
     * The name of the remapped artifactId. So you may want to use a different artifactId and keep
     * the standard version. If the original artifactId was "foo" then the final artifact would
     * be something like foo-1.0.jar. So if you change the artifactId you might have something
     * like foo-special-1.0.jar.
     */
    @Parameter( defaultValue = "${project.artifactId}" )
    private String remappedArtifactId;

    /**
     * Defines whether the remapped artifact should be attached as classifier to
     * the original artifact.  If false, the remapped jar will be the main artifact
     * of the project
     */
    @Parameter
    private boolean remappedArtifactAttached;

    /**
     * The name of the classifier used in case the remapped artifact is attached.
     */
    @Parameter( defaultValue = "remapped" )
    private String remappedClassifierName;

    /**
     * Mapping input file and options
     */
    @Parameter( required = true )
    private String srgIn;
    @Parameter
    private boolean reverse;
    @Parameter
    private boolean numeric;
    @Parameter
    private String inShadeRelocation;
    @Parameter
    private String outShadeRelocation;

    public void execute() throws MojoExecutionException {
        if (project.getArtifact().getFile() == null || !project.getArtifact().getFile().isFile()) {
            // message borrowed from maven-shade-plugin
            getLog().error( "The project main artifact does not exist. This could have the following" );
            getLog().error( "reasons:" );
            getLog().error( "- You have invoked the goal directly from the command line. This is not" );
            getLog().error( "  supported. Please add the goal to the default lifecycle via an" );
            getLog().error( "  <execution> element in your POM and use \"mvn package\" to have it run." );
            getLog().error( "- You have bound the goal to a lifecycle phase before \"package\". Please" );
            getLog().error( "  remove this binding from your POM such that the goal will be run in" );
            getLog().error( "  the proper phase." );
            getLog().error(
                    "- You removed the configuration of the maven-jar-plugin that produces the main artifact." );
            throw new MojoExecutionException(
                    "Failed to create remapped artifact, " + "project main artifact does not exist." );
        }

        File inputJar = project.getArtifact().getFile();
        File outputJar = remappedArtifactFileWithClassifier();

        try {
            JarMapping mapping = new JarMapping();

            mapping.loadMappings(srgIn, reverse, numeric, inShadeRelocation, outShadeRelocation);

            JarRemapper remapper = new JarRemapper(mapping);
            remapper.remapJar(Jar.init(inputJar), outputJar);

            if (remappedArtifactAttached) {
                getLog().info("Attaching remapped artifact.");
                projectHelper.attachArtifact( project, project.getArtifact().getType(), remappedClassifierName,
                        outputJar );
            } else {
                getLog().info("Replacing original artifact with remapped artifact.");
                File originalArtifact = project.getArtifact().getFile();
                replaceFile( originalArtifact, outputJar );
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            throw new MojoExecutionException("Error creating remapped jar: " + ex.getMessage(), ex);
        }

    }


    private File remappedArtifactFileWithClassifier() {
        Artifact artifact = project.getArtifact();
        final String shadedName = remappedArtifactId + "-" + artifact.getVersion() + "-" + remappedClassifierName + "."
                + artifact.getArtifactHandler().getExtension();
        return new File( outputDirectory, shadedName );
    }

    private void replaceFile( File oldFile, File newFile )
            throws MojoExecutionException
    {
        getLog().info( "Replacing " + oldFile + " with " + newFile );

        File origFile = new File( outputDirectory, "original-" + oldFile.getName() );
        if ( oldFile.exists() && !oldFile.renameTo( origFile ) )
        {
            //try a gc to see if an unclosed stream needs garbage collecting
            System.gc();
            System.gc();

            if ( !oldFile.renameTo( origFile ) )
            {
                // Still didn't work.   We'll do a copy
                try
                {
                    FileOutputStream fout = new FileOutputStream( origFile );
                    FileInputStream fin = new FileInputStream( oldFile );
                    try
                    {
                        IOUtil.copy(fin, fout);
                    }
                    finally
                    {
                        IOUtil.close( fin );
                        IOUtil.close( fout );
                    }
                }
                catch ( IOException ex )
                {
                    //kind of ignorable here.   We're just trying to save the original
                    getLog().warn( ex );
                }
            }
        }
        if ( !newFile.renameTo( oldFile ) )
        {
            //try a gc to see if an unclosed stream needs garbage collecting
            System.gc();
            System.gc();

            if ( !newFile.renameTo( oldFile ) )
            {
                // Still didn't work.   We'll do a copy
                try
                {
                    FileOutputStream fout = new FileOutputStream( oldFile );
                    FileInputStream fin = new FileInputStream( newFile );
                    try
                    {
                        IOUtil.copy( fin, fout );
                    }
                    finally
                    {
                        IOUtil.close( fin );
                        IOUtil.close( fout );
                    }
                }
                catch ( IOException ex )
                {
                    throw new MojoExecutionException( "Could not replace original artifact with remapped artifact!", ex );
                }
            }
        }
    }
}
