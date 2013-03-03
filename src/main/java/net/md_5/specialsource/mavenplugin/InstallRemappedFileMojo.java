package net.md_5.specialsource.mavenplugin;

import net.md_5.specialsource.*;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.installer.ArtifactInstallationException;
import org.apache.maven.artifact.installer.ArtifactInstaller;
import org.apache.maven.artifact.metadata.ArtifactMetadata;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.DefaultArtifactRepository;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.digest.Digester;
import org.codehaus.plexus.digest.DigesterException;
import org.codehaus.plexus.util.FileUtils;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.project.artifact.ProjectArtifactMetadata;
import org.apache.maven.project.validation.ModelValidationResult;
import org.apache.maven.project.validation.ModelValidator;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.WriterFactory;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.net.MalformedURLException;
import java.util.*;

/**
 * Install remapped dependencies
 *
 * Based on maven-install-plugin
 */
@Mojo(name = "install-remapped-file", defaultPhase = LifecyclePhase.INITIALIZE)
public class InstallRemappedFileMojo extends AbstractMojo {

    @Component
    protected ArtifactFactory artifactFactory;

    @Component
    protected ArtifactInstaller installer;

    @Parameter( property = "localRepository", required = true, readonly = true )
    protected ArtifactRepository localRepository;

    /**
     * Flag whether to create checksums (MD5, SHA-1) or not.
     *
     * @since 2.2
     */
    @Parameter( property = "createChecksum", defaultValue = "false" )
    protected boolean createChecksum;

    /**
     * Whether to update the metadata to make the artifact a release version.
     */
    @Parameter( property = "updateReleaseInfo", defaultValue = "false" )
    protected boolean updateReleaseInfo;

    /**
     * Digester for MD5.
     */
    @Component( hint = "md5" )
    protected Digester md5Digester;

    /**
     * Digester for SHA-1.
     */
    @Component( hint = "sha1" )
    protected Digester sha1Digester;


    /**
     * GroupId of the artifact to be installed.
     */
    @Parameter( property = "groupId", required = true )
    protected String groupId;

    /**
     * ArtifactId of the artifact to be installed.
     */
    @Parameter( property = "artifactId", required = true )
    protected String artifactId;

    /**
     * Version of the artifact to be installed.
     */
    @Parameter( property = "version", required = true )
    protected String version;

    /**
     * Packaging type of the artifact to be installed.
     */
    @Parameter( property = "packaging", defaultValue = "jar" )
    protected String packaging;

    /**
     * Classifier type of the artifact to be installed. For example, "sources" or "javadoc". Defaults to none which
     * means this is the project's main artifact.
     *
     */
    @Parameter( property = "classifier" )
    protected String classifier;

    /**
     * The input jar(s) to be remapped and installed. May be remote URLs.
     */
    @Parameter( property = "in-jars", required = true )
    private String[] inJars;

    /**
     * The bundled API docs for the artifact.
     *
     */
    @Parameter( property = "javadoc" )
    private File javadoc;

    /**
     * The bundled sources for the artifact.
     *
     */
    @Parameter( property = "sources" )
    private File sources;

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

    /**
     * Map that contains the repository layouts.
     */
    @Component( role = ArtifactRepositoryLayout.class )
    private Map repositoryLayouts;

    /**
     * The current Maven project.
     */
    @Component
    private MavenProject project;

    @Component
    private MavenProjectHelper projectHelper;

    /**
     * The component used to validate the user-supplied artifact coordinates.
     */
    @Component
    private ModelValidator modelValidator; // TODO: what is the non-deprecated replacement for mvn 3? maven-install-plugin uses this but its on mvn 2.0.6

    public void execute() throws MojoFailureException, MojoExecutionException {
        // Validate
        validateArtifactInformation();

        Artifact artifact =
                artifactFactory.createArtifactWithClassifier( groupId, artifactId, version, packaging, classifier );

        /* TODO
        if ( outJar.equals( getLocalRepoFile( artifact ) ) )
        {
            throw new MojoFailureException(
                    "Cannot install artifact. " + "Artifact is already in the local repository.\n\nFile in question is: "
                            + inJar + "\n" );
        }
        */

        // Remap
        File outJar = null;
        try {
            // load input jar(s), as one combined jar (for jarmods)
            List<File> files = new ArrayList<File>();
            for (String filename : inJars) {
                files.add(URLDownloader.getLocalFile(filename));
            }
            Jar inJar = Jar.init(files);

            // temporary output file TODO: cleanup
            outJar = File.createTempFile(groupId + "." + artifactId + "-" + version, "." + packaging);

            // mappings
            JarMapping mapping = new JarMapping();
            mapping.loadMappings(srgIn, reverse, numeric, inShadeRelocation, outShadeRelocation);
            mapping.setFallbackInheritanceProvider(new JarInheritanceProvider(inJar));

            // remap
            JarRemapper remapper = new JarRemapper(mapping);
            remapper.remapJar(inJar, outJar);
        } catch (Exception ex) {
            ex.printStackTrace();
            throw new MojoExecutionException("Error creating remapped jar: " + ex.getMessage(), ex);
        }


        // Install POM
        File generatedPomFile = generatePomFile();
        ArtifactMetadata pomMetadata = new ProjectArtifactMetadata( artifact, generatedPomFile );
        if ( !getLocalRepoFile( pomMetadata ).exists() )
        {
            getLog().debug( "Installing generated POM" );
            artifact.addMetadata( pomMetadata );
        }
        else
        {
            getLog().debug( "Skipping installation of generated POM, already present in local repository" );
        }

        if ( updateReleaseInfo )
        {
            artifact.setRelease( true );
        }


        // Install artifact (based on maven-install-plugin)

        Collection metadataFiles = new LinkedHashSet();

        try
        {
            installer.install( outJar, artifact, localRepository );
            installChecksums( artifact, metadataFiles );
        }
        catch ( ArtifactInstallationException e )
        {
            throw new MojoExecutionException(
                    "Error installing artifact '" + artifact.getDependencyConflictId() + "': " + e.getMessage(), e );
        }
        finally
        {
            if ( generatedPomFile != null )
            {
                generatedPomFile.delete();
            }
        }

        if ( sources != null )
        {
            artifact = artifactFactory.createArtifactWithClassifier( groupId, artifactId, version, "jar", "sources" );
            try
            {
                installer.install( sources, artifact, localRepository );
                installChecksums( artifact, metadataFiles );
            }
            catch ( ArtifactInstallationException e )
            {
                throw new MojoExecutionException( "Error installing sources " + sources + ": " + e.getMessage(), e );
            }
        }

        if ( javadoc != null )
        {
            artifact = artifactFactory.createArtifactWithClassifier( groupId, artifactId, version, "jar", "javadoc" );
            try
            {
                installer.install( javadoc, artifact, localRepository );
                installChecksums( artifact, metadataFiles );
            }
            catch ( ArtifactInstallationException e )
            {
                throw new MojoExecutionException( "Error installing API docs " + javadoc + ": " + e.getMessage(), e );
            }
        }

        installChecksums( metadataFiles );

        /* TODO: set as dependency?
        may not be feasible to add dynamically - instead, add in your pom.xml by hand then 'mvn initialize' & 'mvn package'

        mvn dependency:list

        http://maven.40175.n5.nabble.com/How-to-add-a-dependency-dynamically-during-the-build-in-a-plugin-td116484.html
        "How to add a dependency dynamically during the build in a plugin?"
        no solution

        https://gist.github.com/agaricusb/5073308/raw/aeae7738ec5da5274eab143d9f92c439f9b07f47/gistfile1.txt
        "Maven: A Developer's Notebook - Chapter 6. Writing Maven Plugins > Adding Dynamic Dependencies"
        adding to classpath..

        // Add dependency
        Dependency dependency = new Dependency();
        dependency.setGroupId(groupId);
        dependency.setArtifactId(artifactId);
        dependency.setVersion(version);
        dependency.setScope("compile"); // TODO: system?
        //dependency.setSystemPath();

        project.getDependencies().add(dependency);*/
    }

    /**
     * Validates the user-supplied artifact information.
     *
     * @throws MojoExecutionException If any artifact coordinate is invalid.
     */
    private void validateArtifactInformation()
            throws MojoExecutionException
    {
        Model model = generateModel();

        ModelValidationResult result = modelValidator.validate( model );

        if ( result.getMessageCount() > 0 )
        {
            throw new MojoExecutionException(
                    "The artifact information is incomplete or not valid:\n" + result.render( "  " ) );
        }
    }


    /**
     * Generates a minimal model from the user-supplied artifact information.
     *
     * @return The generated model, never <code>null</code>.
     */
    private Model generateModel()
    {
        Model model = new Model();

        model.setModelVersion( "4.0.0" );

        model.setGroupId( groupId );
        model.setArtifactId( artifactId );
        model.setVersion( version );
        model.setPackaging( packaging );

        model.setDescription( "POM was created from specialsource-maven-plugin" );

        return model;
    }

    /**
     * Generates a (temporary) POM file from the plugin configuration. It's the responsibility of the caller to delete
     * the generated file when no longer needed.
     *
     * @return The path to the generated POM file, never <code>null</code>.
     * @throws MojoExecutionException If the POM file could not be generated.
     */
    private File generatePomFile()
            throws MojoExecutionException
    {
        Model model = generateModel();

        Writer writer = null;
        try
        {
            File pomFile = File.createTempFile( "mvninstall", ".pom" );

            writer = WriterFactory.newXmlWriter(pomFile);
            new MavenXpp3Writer().write( writer, model );

            return pomFile;
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Error writing temporary POM file: " + e.getMessage(), e );
        }
        finally
        {
            IOUtil.close(writer);
        }
    }

    // TODO: extend maven-install-plugin AbstractInstallMojo??

    /**
     * Gets the path of the specified artifact within the local repository. Note that the returned path need not exist
     * (yet).
     *
     * @param artifact The artifact whose local repo path should be determined, must not be <code>null</code>.
     * @return The absolute path to the artifact when installed, never <code>null</code>.
     */
    protected File getLocalRepoFile( Artifact artifact )
    {
        String path = localRepository.pathOf( artifact );
        return new File( localRepository.getBasedir(), path );
    }

    /**
     * Gets the path of the specified artifact metadata within the local repository. Note that the returned path need
     * not exist (yet).
     *
     * @param metadata The artifact metadata whose local repo path should be determined, must not be <code>null</code>.
     * @return The absolute path to the artifact metadata when installed, never <code>null</code>.
     */
    protected File getLocalRepoFile( ArtifactMetadata metadata )
    {
        String path = localRepository.pathOfLocalRepositoryMetadata( metadata, localRepository );
        return new File( localRepository.getBasedir(), path );
    }

    /**
     * Installs the checksums for the specified artifact if this has been enabled in the plugin configuration. This
     * method creates checksums for files that have already been installed to the local repo to account for on-the-fly
     * generated/updated files. For example, in Maven 2.0.4- the <code>ProjectArtifactMetadata</code> did not install
     * the original POM file (cf. MNG-2820). While the plugin currently requires Maven 2.0.6, we continue to hash the
     * installed POM for robustness with regard to future changes like re-introducing some kind of POM filtering.
     *
     * @param artifact The artifact for which to create checksums, must not be <code>null</code>.
     * @param metadataFiles The set where additional metadata files will be registered for later checksum installation,
     *            must not be <code>null</code>.
     * @throws MojoExecutionException If the checksums could not be installed.
     */
    protected void installChecksums( Artifact artifact, Collection metadataFiles )
            throws MojoExecutionException
    {
        if ( !createChecksum )
        {
            return;
        }

        File artifactFile = getLocalRepoFile( artifact );
        installChecksums( artifactFile );

        Collection metadatas = artifact.getMetadataList();
        if ( metadatas != null )
        {
            for ( Iterator it = metadatas.iterator(); it.hasNext(); )
            {
                ArtifactMetadata metadata = (ArtifactMetadata) it.next();
                File metadataFile = getLocalRepoFile( metadata );
                metadataFiles.add( metadataFile );
            }
        }
    }

    /**
     * Installs the checksums for the specified metadata files.
     *
     * @param metadataFiles The collection of metadata files to install checksums for, must not be <code>null</code>.
     * @throws MojoExecutionException If the checksums could not be installed.
     */
    protected void installChecksums( Collection metadataFiles )
            throws MojoExecutionException
    {
        for ( Iterator it = metadataFiles.iterator(); it.hasNext(); )
        {
            File metadataFile = (File) it.next();
            installChecksums( metadataFile );
        }
    }

    /**
     * Installs the checksums for the specified file (if it exists).
     *
     * @param installedFile The path to the already installed file in the local repo for which to generate checksums,
     *            must not be <code>null</code>.
     * @throws MojoExecutionException If the checksums could not be installed.
     */
    private void installChecksums( File installedFile )
            throws MojoExecutionException
    {
        boolean signatureFile = installedFile.getName().endsWith( ".asc" );
        if ( installedFile.isFile() && !signatureFile )
        {
            installChecksum( installedFile, installedFile, md5Digester, ".md5" );
            installChecksum( installedFile, installedFile, sha1Digester, ".sha1" );
        }
    }

    /**
     * Installs a checksum for the specified file.
     *
     * @param originalFile The path to the file from which the checksum is generated, must not be <code>null</code>.
     * @param installedFile The base path from which the path to the checksum files is derived by appending the given
     *            file extension, must not be <code>null</code>.
     * @param digester The checksum algorithm to use, must not be <code>null</code>.
     * @param ext The file extension (including the leading dot) to use for the checksum file, must not be
     *            <code>null</code>.
     * @throws MojoExecutionException If the checksum could not be installed.
     */
    private void installChecksum( File originalFile, File installedFile, Digester digester, String ext )
            throws MojoExecutionException
    {
        String checksum;
        getLog().debug( "Calculating " + digester.getAlgorithm() + " checksum for " + originalFile );
        try
        {
            checksum = digester.calc( originalFile );
        }
        catch ( DigesterException e )
        {
            throw new MojoExecutionException( "Failed to calculate " + digester.getAlgorithm() + " checksum for "
                    + originalFile, e );
        }

        File checksumFile = new File( installedFile.getAbsolutePath() + ext );
        getLog().debug( "Installing checksum to " + checksumFile );
        try
        {
            checksumFile.getParentFile().mkdirs();
            FileUtils.fileWrite( checksumFile.getAbsolutePath(), "UTF-8", checksum );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Failed to install checksum to " + checksumFile, e );
        }
    }
}
