pipeline {
    agent {
        kubernetes {
            label config.label ?: 'java8'
            defaultContainer config.container ?: 'java8'
        }
    }
    parameters {
        choice(name: 'BUILD_TYPE', choices: ['build', 'release'], description: 'Select Build or Release')
        string(name: 'branch', defaultValue: config.defaultBranch ?: 'master', description: 'Branch to build')
        booleanParam(name: 'MAVEN_DRY_RUN', defaultValue: false, description: 'Dry run only?')
        string(name: 'DEVELOPMENT_VERSION', defaultValue: '', description: 'Next snapshot version (only for release builds). Leave blank for default version')
        string(name: 'SCM_TAG', defaultValue: '', description: 'Custom SCM tag for release (optional)')
    }
    environment {
        JAVA_HOME = ''
        PATH = ''
        MAVEN_HOME = ''
        REPOSITORY = ''
        POM_PATH = ''
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '5'))
    }
    triggers {
        cron(config.cron ?: 'H H(0-7) * * *')
    }
    stages {
        stage('Verify and Checkout') {
            steps {
                script {
                    initEnv(config)
                    if(params.BUILD_TYPE == 'release') {
                        println "Verifying user permissions for release..."
                        def roles = getRoles()
                        println "User roles: ${roles}"
                        if (!(['release_admin', 'admin'] as List).intersect(roles)) {
                            currentBuild.result = "ABORTED"
                            error("User not authorized to perform a release.")
                        }
                    }
                    checkout([$class: 'GitSCM',
                              branches: [[name: "*/${params.branch}"]],
                              userRemoteConfigs: [[url: REPOSITORY,
                                                   credentialsId: config.credentialsId ?: 'jenkins-bitbucket-cred']]
                    ])
                    sh "git checkout ${params.branch}"
                }
            }
        }
        stage('Build') {
            when {
                expression { params.BUILD_TYPE == 'build' }
            }
            steps {
                script {
                    withCredentials([
                        file(credentialsId: 'maven-settings-xml', variable: 'MAVEN_SETTINGS')
                    ]) {
                        def buildGoals = config.buildGoals ?: "clean install"
                        if (params.MAVEN_DRY_RUN) {
                            buildGoals += " -DdryRun=true"
                        }
                        sh """
                            ${MAVEN_HOME}/bin/mvn -s ${MAVEN_SETTINGS} ${buildGoals}
                        """
                    }
                }
            }
        }
        stage('Release') {
            when {
                expression { params.BUILD_TYPE == 'release' }
            }
            steps {
                script {
                    withCredentials([
                        usernamePassword(credentialsId: 'jenkins-bitbucket-cred', usernameVariable: 'username', passwordVariable: 'appPassword'),
                        file(credentialsId: 'maven-settings-xml', variable: 'MAVEN_SETTINGS')
                    ]) { 
                        // To set up if no git congfig is set
                        // sh """
                        //     git config user.name "$username"
                        //     git config user.email "User@email.com"
                        // """
                        def releaseGoals = config.releaseGoals ?: "release:prepare release:perform -Dresume=false -Dmaven.javadoc.skip=true -Darguments=\"-Dmaven.javadoc.skip=true\" -Dusername=${env.username} -Dpassword=${env.appPassword}"
                        if (params.MAVEN_DRY_RUN) {
                            releaseGoals += " -DdryRun=true"
                        }
                        if (params.SCM_TAG?.trim()) {
                            releaseGoals += " -Dtag=${params.SCM_TAG}"
                        }
                        if (params.DEVELOPMENT_VERSION?.trim()) {
                            releaseGoals += " -DdevelopmentVersion=${params.DEVELOPMENT_VERSION}"
                        }
                        sh """
                            ${MAVEN_HOME}/bin/mvn -s ${MAVEN_SETTINGS} ${releaseGoals}
                        """
                    }
                }
            }
        }
    }
    post {
        success {
            echo 'Build completed successfully!'
            script {
                def pomXml = readMavenPom file: POM_PATH
                def tag = "${pomXml.artifactId}-${pomXml.version.replace('-SNAPSHOT', '')}"
                if (params.DEVELOPMENT_VERSION?.trim()) {
                    sh "mvn -f ${POM_PATH} versions:set -DnewVersion=${params.DEVELOPMENT_VERSION}"
                } else {
                    sh "mvn -f ${POM_PATH} versions:set -DnextSnapshot"
                }
                pomXml = readMavenPom file: POM_PATH
                def version = pomXml.version
                setParamDefaultValue(env.JOB_NAME, 'DEVELOPMENT_VERSION', version)
                setParamDefaultValue(env.JOB_NAME, 'SCM_TAG', tag)
            }
        }
        failure {
            echo 'Build failed!'
        }
    }
}


def initEnv(config){
    echo 'Initializing environment variables...'
    JAVA_HOME = config.javaHome ?: "/usr/local/openjdk-8"
    PATH = "${config.javaHome ?: '/usr/local/openjdk-8'}/bin:${env.PATH}"
    MAVEN_HOME = config.mavenHome ?: "/usr/share/maven"
    REPOSITORY = config.repository ?: ''
    POM_PATH = config.pomPath ?: "pom.xml"
}

def getRoles(userId="") {
    if(!userId){
        userId = currentBuild.rawBuild.getCause(Cause.UserIdCause).getUserId()
    }
    def authStrategy = Hudson.instance.getAuthorizationStrategy()
    def permissions = authStrategy.roleMaps.inject([:]){map, i -> map + i.value.grantedRoles}
    def roles= permissions.findAll{ it.value.contains(userId) }.collect{it.key.name}
    return roles
}

def setParamDefaultValue(String jobName, String paramName, String newDefaultValue) {
    def job = Jenkins.instance.getItemByFullName(jobName)

    if (job) {
        def paramsDefProperty = job.getProperty(hudson.model.ParametersDefinitionProperty)
        
        if (paramsDefProperty) {
            def param = paramsDefProperty.parameterDefinitions.find { it.name == paramName }
            
            if (param) {
                param.defaultValue = newDefaultValue
                job.save()
                println("Updated default value of parameter '${paramName}' to '${newDefaultValue}' for job '${jobName}'")
            } else {
                println("Parameter '${paramName}' not found in job '${jobName}'")
            }
        } else {
            println("No parameters found for job '${jobName}'")
        }
    } else {
        println("Job '${jobName}' not found")
    }
}
