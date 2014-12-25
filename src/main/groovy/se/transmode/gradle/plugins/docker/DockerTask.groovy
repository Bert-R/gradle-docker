/**
 * Copyright 2014 Transmode AB
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
package se.transmode.gradle.plugins.docker
import com.google.common.annotations.VisibleForTesting
import com.google.common.io.Files
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.TaskAction
import se.transmode.gradle.plugins.docker.client.DockerClient
import se.transmode.gradle.plugins.docker.image.Dockerfile

class DockerTask extends DockerTaskBase {

    private static Logger logger = Logging.getLogger(DockerTask)
    public static final String DEFAULT_IMAGE = 'ubuntu'

    // Name and Email of the image maintainer
    String maintainer
    // Whether or not to execute docker to build the image (default: false)
    Boolean dryRun
    // Whether or not to push the image into the registry (default: false)
    Boolean push

    Dockerfile dockerfile
    File externalDockerfile

    public void dockerfile(Closure closure) {
        dockerfile.with(closure)
    }


    /**
     * Path to external Dockerfile
     */
    public void setDockerfile(String path) {
        dockerfile(project.file(path))
    }
    public void setDockerfile(File baseFile) {
        logger.info('Creating Dockerfile from file {}.', baseFile)
        dockerfile.from(baseFile)
    }

    /**
     * Name of base docker image
    */
    String baseImage
    /**
     * Return the base docker image.
     *
     * If the base image is set in the task, return it. Otherwise return the base image
     * defined in the 'docker' extension. If the extension base image is not set determine
     * base image based on the 'targetCompatibility' property from the java plugin.
     *
     * @return Name of base docker image
     */
    public String getBaseImage() {
        def defaultImage = project.hasProperty('targetCompatibility') ? JavaBaseImage.imageFor(project.targetCompatibility).imageName : DEFAULT_IMAGE
        return baseImage ?: (project[DockerPlugin.EXTENSION_NAME].baseImage ?: defaultImage)
    }


    // Dockerfile instructions (ADD, RUN, etc.)
    def instructions
    // Dockerfile staging area i.e. context dir
    File stageDir
    // Tasks necessary to setup the stage before building an image
    def stageBacklog
    
    DockerTask() {
        instructions = []
        stageBacklog = []
        dockerfile = new Dockerfile({ -> project.file(it) }, { -> project.copy(it) })
        stageDir = new File(project.buildDir, "docker")
    }

    void addFile(String source, String destination='/') {
        addFile(project.file(source), destination)
    }

    void addFile(File source, String destination='/') {
        def target = stageDir
        if (source.isDirectory()) {
            target = new File(stageDir, source.name)
        }
        stageBacklog.add { ->
            project.copy {
                from source
                into target
            }
        }
        instructions.add("ADD ${source.name} ${destination}")
    }

    void addFile(Closure copySpec) {
        final tarFile = new File(stageDir, "add_${instructions.size()+1}.tar")
        stageBacklog.add { ->
            createTarArchive(tarFile, copySpec)
        }
        instructions.add("ADD ${tarFile.name} ${'/'}")
    }

    void createTarArchive(File tarFile, Closure copySpec) {
        final tmpDir = Files.createTempDir()
        logger.info("Creating tar archive {} from {}", tarFile, tmpDir)
        /* copy all files to temporary directory */
        project.copy {
            with {
                into('/') {
                    with copySpec
                }
            }
            into tmpDir
        }
        /* create tar archive */
        new AntBuilder().tar(
                destfile: tarFile,
                basedir: tmpDir
        )
    }

    void workingDir(String wd) {
        instructions.add("WORKDIR ${wd}")
    }

    void addInstruction(String cmd, String value) {
        instructions.add("${cmd} ${value}")
    }

    void runCommand(String command) {
        instructions.add("RUN ${command}")
    }

    void exposePort(Integer port) {
        instructions.add("EXPOSE ${port}")
    }

    void switchUser(String userName) {
        instructions.add("USER ${userName}")
    }

    void setEnvironment(String key, String value) {
        instructions.add("ENV ${key} ${value}")
    }

    void volume(String... paths) {
        instructions.add('VOLUME ["' + paths.join('", "') + '"]')
    }

    void setEntryPoint(List entryPoint) {
        instructions.add('ENTRYPOINT ["' + entryPoint.join('", "') + '"]')
    }

    void entryPoint(List entryPoint) {
        this.setEntryPoint(entryPoint)
    }

    @Deprecated
    void setDefaultCommand(List cmd) {
        logger.warn('The setDefaultCommand method thas been deprecated and is scheduled to be removed. Use dockerfile.cmd instead.')
        dockerfile.cmd(cmd)
    }

    @Deprecated
    void defaultCommand(List cmd) {
        this.setDefaultCommand(cmd)
    }

    void contextDir(String contextDir) {
        stageDir = new File(stageDir, contextDir)
    }

    private File createDirIfNotExists(File dir) {
        if (!dir.exists())
            dir.mkdirs()
        return dir
    }
    
    @VisibleForTesting
    protected void setupStageDir() {
        logger.info('Setting up staging directory.')
        createDirIfNotExists(stageDir)
        stageBacklog.each() { it() }
    }

    @VisibleForTesting
    protected Dockerfile buildDockerfile() {
        if (!dockerfile.hasBase()) {
            def baseImage = getBaseImage()
            logger.info('Creating Dockerfile from base {}.', baseImage)
            dockerfile.from(baseImage)
        }
        if (getMaintainer()) {
            dockerfile.maintainer(getMaintainer())
        }
        return dockerfile.appendAll(instructions)
    }

    @TaskAction
    void build() {
        setupStageDir()
        buildDockerfile().writeToFile(new File(stageDir, 'Dockerfile'))
        tag = getImageTag()
        logger.info('Determining image tag: {}', tag)

        if (!dryRun) {
            DockerClient client = getClient()
            println client.buildImage(stageDir, tag)
            if (push) {
                println client.pushImage(tag)
            }
        }

    }
    
}
