#!groovy

/*************************************************************************
 *
 * KLEAREXPRESS CONFIDENTIAL
 * __________________
 *
 *  Copyright (c) 2018 - 2018 KlearExpress Corporation.
 *  All Rights Reserved.
 */

final kxlib = library('kxlib@development')

def call(Map pipelineParams) {
  // execute this before anything else, including requesting any time on an agent
  if (currentBuild.rawBuild.getCauses().toString().contains('BranchIndexingCause')) {
    print "INFO: Build skipped due to trigger being Branch Indexing"
    return
  }
  def environments_info = [
    blue: [NAME: 'BLUE', EKS_NAMESPACE: 'prod', EKS_KUBECONFIG: 'eks_kubeconfig_prod'],
    green: [NAME: 'GREEN', EKS_NAMESPACE: 'stage', EKS_KUBECONFIG: 'eks_kubeconfig_stage']
  ]
  def branch_environments = [
    development: [NAME: 'development', DOCKER_PREFIX_UNIQUEID: 'dev-', EKS_NAMESPACE: 'dev', SHARED_EKS_NAMESPACE: 'NA', EKS_KUBECONFIG: 'eks_kubeconfig_dev', SSL_ARN: 'arn:aws:acm:us-west-2:035592488042:certificate/7c26c813-9bda-4201-bfad-811aa3927a15', SPIN_PROMOTE_TO_ENV: 'staging'],
    staging: [NAME: 'staging', DOCKER_PREFIX_UNIQUEID: 'stage-', EKS_NAMESPACE: '--KXENVTOGGLE--', SHARED_EKS_NAMESPACE: 'NA', EKS_KUBECONFIG: '--KXENVTOGGLE--', SSL_ARN: 'arn:aws:acm:us-east-1:035592488042:certificate/49647955-58f2-4a27-af10-07630f77414f', SPIN_PROMOTE_TO_ENV: 'master'],
    qat: [NAME: 'qat', DOCKER_PREFIX_UNIQUEID: 'qat-', EKS_NAMESPACE: 'qat', SHARED_EKS_NAMESPACE: 'NA', EKS_KUBECONFIG: 'eks_kubeconfig_qat', SSL_ARN: 'arn:aws:acm:us-east-1:035592488042:certificate/84b52da1-b05b-4212-9e38-b830e6176a73', SPIN_PROMOTE_TO_ENV: 'master'],
    master: [NAME: 'production', DOCKER_PREFIX_UNIQUEID: 'prod-', EKS_NAMESPACE: '--KXENVTOGGLE--', SHARED_EKS_NAMESPACE: 'NA', EKS_KUBECONFIG: '--KXENVTOGGLE--', SSL_ARN: 'arn:aws:acm:us-east-1:035592488042:certificate/8e1b1c20-b9af-43f6-85af-88929ef2e33a', SPIN_PROMOTE_TO_ENV: 'prodmaster'],
    prodmaster: [NAME: 'production', DOCKER_PREFIX_UNIQUEID: 'prod-', EKS_NAMESPACE: '--KXENVTOGGLE--', SHARED_EKS_NAMESPACE: 'NA', EKS_KUBECONFIG: '--KXENVTOGGLE--', SSL_ARN: 'arn:aws:acm:us-east-1:035592488042:certificate/8e1b1c20-b9af-43f6-85af-88929ef2e33a']
  ]
  // DEFAULT CLUSTER FOR ALL BRANCH deployments
  def other_branch_environment = "development"

  // Set node type based on branch
  def karpenter_node = (env.BRANCH_NAME == 'master') ? 'karpenter-prod' : 'karpenter'

//////////////////////////////////////////////////////////////////////////////////
  // Dynamically generate the YAML configuration with the correct node selector
  def podYaml = """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: go
    image: 035592488042.dkr.ecr.us-west-2.amazonaws.com/jenkins-agents:new-gospin
    command:
    - cat
    tty: true
    volumeMounts:
    - mountPath: /var/lib/jenkins
      name: persistent-storage
  - name: ghcli
    image: 035592488042.dkr.ecr.us-west-2.amazonaws.com/jenkins-agents:Ghcli
    command:
    - cat
    tty: true
    volumeMounts:
    - mountPath: /var/lib/jenkins
      name: persistent-storage
  - name: docker
    image: 035592488042.dkr.ecr.us-west-2.amazonaws.com/jenkins-agents:docker-orcacli
    tty: true
    securityContext:
      privileged: true
      capabilities:
        drop:
        - ALL
        add:
        - NET_BIND_SERVICE
  nodeSelector:
    type: ${karpenter_node}
  securityContext:
    seLinuxOptions:
      type: spc_t
  volumes:
  - name: persistent-storage
    persistentVolumeClaim:
      claimName: efs-jenkins-claim
  """
  pipeline {
        agent {
      kubernetes {
        yaml podYaml  // Pass the dynamically generated YAML here
      }
      }
    
    // ========================================
    // PARAMETERS - Environment Selection
    // ========================================
    parameters {
      choice(
        name: 'TARGET_ENVIRONMENT',
        choices: ['Auto (based on branch)', 'development', 'staging', 'qat', 'production'],
        description: 'Select target environment to deploy to. "Auto" will use the branch name to determine the environment.'
      )
      booleanParam(
        name: 'DeployTarget',
        defaultValue: false,
        description: 'Enable target-specific deployment (adds tg- prefix to images)'
      )
      booleanParam(
        name: 'SKIP_ORCA_SCAN',
        defaultValue: false,
        description: 'Skip Orca security scanning (not recommended)'
      )
    }
    
    options {
      buildDiscarder(logRotator(numToKeepStr: '5', daysToKeepStr: '10'))
      disableConcurrentBuilds abortPrevious: true
      preserveStashes(buildCount: 5)
      checkoutToSubdirectory('source')
      // timeout(time: 40, unit: 'MINUTES') 
    }
    environment {
      // KX Environment Configuration to manage with Blue/Green deployments
      KX_ENVIRONMENT_CONFIGURATION_FILE = "kx_environment_configuration.groovy"
      // KX Environment Configuration file path
      KX_ENVIRONMENT_CONFIGURATION_JENKINS_LOAD_PATH = "${WORKSPACE}/.envvars/${KX_ENVIRONMENT_CONFIGURATION_FILE}"
      // MANIFEST FILE TO RENDER - KUBECTL DEPLOY TEMPLATES
      K8S_MANIFEST_JSON = 'k8sManifest.json'
      // PROPERTIES FILE TO KEEP PROJECT VALUES SETUP
      PROJECT_PROPERTIES_FILE = 'build.properties'
      // The HOSTNAME of the AWS ECR repository.
      ECR_HOST = '035592488042.dkr.ecr.us-west-2.amazonaws.com'
      // ENVIRONMENT varible keeps the environment value for tagging labeling and conditions ex- [production]
      ENVIRONMENT = define_environment(branch_environments, other_branch_environment)
      // SPIN_PROMOTE_TO_ENV_NAME varible keeps the environment value for spinnaker next promoted env ex- [pre_production to production]
      SPIN_PROMOTE_TO_ENV_NAME = define_next_promote_environment(branch_environments, other_branch_environment)
      // AWS SSL Cert ARN which will be used by HTTPS Settings per Region ex- [aws certificate dev/prod]
      SPIN_PROMOTE_AWS_SSL_ARN = define_next_promote_aws_ssl_arn(branch_environments, other_branch_environment)
      // XSPIN_PROMOTE_TO_ENV_EKS_NAMESPACE varible keeps the environment namespace value for spinnaker next promoted env ex- [stage to pre-prod]
      XSPIN_PROMOTE_TO_ENV_EKS_NAMESPACE = define_next_promote_environment_eks_namespace(branch_environments, other_branch_environment)
      // DOCKER image tag which will be used to tag build images - branch unique build identifier ex- [prod]
      DOCKER_BUILD_IMAGE_TAG = define_docker_build_image_tag(branch_environments, other_branch_environment)
      // EKS Cluster Namespace where this builds need to be Deploy ex- [prod]
      XEKS_DEPLOYMENT_NAMESPACE = define_eks_deployment_namespace(branch_environments, other_branch_environment)
      // AWS SSL Cert ARN which will be used by HTTPS Settings per Region ex- [aws certificate dev/prod]
      AWS_SSL_ARN = define_aws_ssl_arn(branch_environments, other_branch_environment)
      // Kubeconfig of EKS Cluster to do operations on EKS.
      XKUBECONFIG = define_kubeconfig(branch_environments, other_branch_environment)
      // backward merge 
      checkout = definecheckout_to()
      // Orca Api Token
      ORCA_API_TOKEN = credentials('orca-api-token') 
    }
    tools { 
	    // go 'go-1.17.2'
	    go 'go-1.18'
    }
    stages {
      stage('Environment Selection') {
        steps {
          script {
            // Display selected environment
            echo """
╔════════════════════════════════════════════════════════════════╗
║              BUILD CONFIGURATION                              ║
╠════════════════════════════════════════════════════════════════╣
║  Branch:              ${env.BRANCH_NAME.padRight(40)}║
║  Selected Target:     ${params.TARGET_ENVIRONMENT.padRight(40)}║
║  Deploy Target Mode:  ${params.DeployTarget.toString().padRight(40)}║
║  Skip Orca Scan:      ${params.SKIP_ORCA_SCAN.toString().padRight(40)}║
╠════════════════════════════════════════════════════════════════╣
║  Effective Environment: ${env.ENVIRONMENT.padRight(36)}║
╚════════════════════════════════════════════════════════════════╝
"""
            
            // Override environment if user selected specific target
            if (params.TARGET_ENVIRONMENT != 'Auto (based on branch)') {
              echo "⚠️  OVERRIDE: User selected environment '${params.TARGET_ENVIRONMENT}' instead of branch default '${env.ENVIRONMENT}'"
              env.ENVIRONMENT = params.TARGET_ENVIRONMENT
              
              // Update related environment variables based on override
              def targetBranch = mapEnvironmentToBranch(params.TARGET_ENVIRONMENT)
              echo "   Mapping to branch configuration: ${targetBranch}"
              
              // Recalculate environment-specific variables
              env.DOCKER_BUILD_IMAGE_TAG = define_docker_build_image_tag_for_env(params.TARGET_ENVIRONMENT)
              env.AWS_SSL_ARN = define_aws_ssl_arn_for_env(params.TARGET_ENVIRONMENT, branch_environments)
              env.XEKS_DEPLOYMENT_NAMESPACE = define_eks_deployment_namespace_for_env(params.TARGET_ENVIRONMENT, branch_environments)
              env.XKUBECONFIG = define_kubeconfig_for_env(params.TARGET_ENVIRONMENT, branch_environments)
              
              echo """
╔════════════════════════════════════════════════════════════════╗
║              ENVIRONMENT OVERRIDE APPLIED                     ║
╠════════════════════════════════════════════════════════════════╣
║  New Environment:     ${env.ENVIRONMENT.padRight(40)}║
║  Image Tag:           ${env.DOCKER_BUILD_IMAGE_TAG.padRight(40)}║
║  EKS Namespace:       ${env.XEKS_DEPLOYMENT_NAMESPACE.padRight(40)}║
╚════════════════════════════════════════════════════════════════╝
"""
            }
          }
        }
      }
      
      stage('Bootstrap') {
        when {
          beforeAgent true
          anyOf {
            branch 'development'; branch 'staging'; branch 'qat'; branch 'master'
          }
        }
        steps {
          container('go') {
            dir('source') {
              script {
                if (!fileExists("${WORKSPACE}/source/${env.PROJECT_PROPERTIES_FILE}")) {
                  currentBuild.result = 'ABORTED'
                  error("ABORTED -> Stopping early…. Project Build Properties File Not found [ ${env.PROJECT_PROPERTIES_FILE} ]")
                }
                def props = readProperties file: "${env.PROJECT_PROPERTIES_FILE}"
                env.PROJECT_NAME = props['projectName']
                // Do NOT modify env.PROJECT_NAME
                // Just build MANIFEST_FILE_NAME from it
                if (params.DeployTarget) {
                  echo "Target deployment enabled. Setting manifest and JSON names with tg- prefix."
                  env.MANIFEST_FILE_NAME = env.PROJECT_NAME.startsWith("tg-") ? env.PROJECT_NAME : "tg-${env.PROJECT_NAME}"
                } else {
                  env.MANIFEST_FILE_NAME = env.PROJECT_NAME
                }
                if (props['projectPath']) {
                  env.PROJECT_PATH = props['projectPath']
                } else {
                  env.PROJECT_PATH = '.'
                }
                env.BUILD_PROPERTIES_PROJECT_VERSION = props['projectVersion']
                env.BUILD_PROPERTIES_ENVIRONMENT = props['environment']
                env.REV_MERGE=props['reversemerge']
                env.ONLY_MANIFESTS = props['onlyManifests']
                sh 'git rev-parse HEAD | tr -d "\n" > GIT_COMMIT'
                env.GIT_COMMIT = readFile('GIT_COMMIT')
                env.PROJECT_DOCKER_IMAGE = ""
                //////////////////DORA/////////////////////////
			    	    env.build_start = sh(returnStdout: true, script: 'TZ=Asia/Kolkata date "+%Y-%m-%d %H:%M:%S"').trim()
                echo "${env.build_start}"
                ////////////////////////////////////////////////
                echo "ENVIRONMENT -> ${env.ENVIRONMENT}"
                echo "DOCKER_BUILD_IMAGE_TAG -> ${env.DOCKER_BUILD_IMAGE_TAG}"
                echo "AWS_SSL_ARN -> ${env.AWS_SSL_ARN}"
                env.ENVIRONMENT_BINDING = binding_environment("${env.ENVIRONMENT}")
                echo "${env.BRANCH_NAME} BRANCH - ${env.ENVIRONMENT.toUpperCase()} ENVIRONMENT BINDING -> ${env.ENVIRONMENT_BINDING} FROM KX CONFIG FILE"
                //                   ADD THE KXENVTOGGLE SETTINGS BELOW THIS COMMENT
                //                          TOGGLING EKS NAMESPACE
                if (env.XEKS_DEPLOYMENT_NAMESPACE.equals("--KXENVTOGGLE--")) {
                  echo "TOGGLING - EKS_DEPLOYMENT_NAMESPACE [ ${XEKS_DEPLOYMENT_NAMESPACE} ]"
                  if (!env.ENVIRONMENT_BINDING) {
                    env.CHANGED_LOG = "## KX JENKINS ENVIRONMENT ENTRY MISSING FOR ${ env.ENVIRONMENT } ##"
                    error("ABORTED -> Stopping early…. Environment Enteries Not Available For LEFT [ ${ env.ENVIRONMENT.toUpperCase() } ] in file ${KX_ENVIRONMENT_CONFIGURATION_FILE}")
                  }
                  env.EKS_DEPLOYMENT_NAMESPACE = define_eks_deployment_namespace_toggle(environments_info, env.ENVIRONMENT_BINDING)
                } else {
                  echo "SKIPPING TOGGLING - EKS_DEPLOYMENT_NAMESPACE [ ${XEKS_DEPLOYMENT_NAMESPACE} ]"
                  env.EKS_DEPLOYMENT_NAMESPACE = env.XEKS_DEPLOYMENT_NAMESPACE
                }
                echo "EKS_DEPLOYMENT_NAMESPACE -> ${env.EKS_DEPLOYMENT_NAMESPACE}"
                //                        END
                //                          TOGGLING EKS SPIN NEXT NAMESPACE
                if (env.XSPIN_PROMOTE_TO_ENV_EKS_NAMESPACE.equals("--KXENVTOGGLE--")) {
                  echo "TOGGLING - SPIN_PROMOTE_TO_ENV_EKS_NAMESPACE [ ${XSPIN_PROMOTE_TO_ENV_EKS_NAMESPACE} ]"
                  spin_next_environment_binding = binding_environment("${env.SPIN_PROMOTE_TO_ENV_NAME}")
                  if (!spin_next_environment_binding) {
                    env.CHANGED_LOG = "## KX JENKINS ENVIRONMENT ENTRY MISSING FOR ${ env.SPIN_PROMOTE_TO_ENV_NAME } ##"
                    error("ABORTED -> Stopping early…. Environment Enteries Not Available For LEFT [ ${ env.SPIN_PROMOTE_TO_ENV_NAME.toUpperCase() } ] in file ${KX_ENVIRONMENT_CONFIGURATION_FILE}")
                  }
                  env.SPIN_PROMOTE_TO_ENV_EKS_NAMESPACE = define_eks_deployment_namespace_toggle(environments_info, spin_next_environment_binding)
                } else {
                  echo "SKIPPING TOGGLING - SPIN_PROMOTE_TO_ENV_EKS_NAMESPACE [ ${XSPIN_PROMOTE_TO_ENV_EKS_NAMESPACE} ]"
                  env.SPIN_PROMOTE_TO_ENV_EKS_NAMESPACE = env.XSPIN_PROMOTE_TO_ENV_EKS_NAMESPACE
                }
                echo "SPIN_PROMOTE_TO_ENV_EKS_NAMESPACE -> ${env.SPIN_PROMOTE_TO_ENV_EKS_NAMESPACE}"
                //                        END
                //                        TOGGLING KUBECONFIG
                if (env.XKUBECONFIG.equals("--KXENVTOGGLE--")) {
                  echo "TOGGLING - KUBECONFIG [ ${XKUBECONFIG} ]"
                  if (!env.ENVIRONMENT_BINDING) {
                    env.CHANGED_LOG = "## KX JENKINS ENVIRONMENT ENTRY MISSING FOR ${ env.ENVIRONMENT } ##"
                    error("ABORTED -> Stopping early…. Environment Enteries Not Available For LEFT [ ${ env.ENVIRONMENT.toUpperCase() } ] in file ${KX_ENVIRONMENT_CONFIGURATION_FILE}")
                  }
                  env.KUBECONFIG = define_kubeconfig_toggle(environments_info, env.ENVIRONMENT_BINDING)
                } else {
                  echo "SKIPPING TOGGLING - KUBECONFIG [ ${XKUBECONFIG} ]"
                  env.KUBECONFIG = env.XKUBECONFIG
                }
                echo "KUBECONFIG -> ${env.KUBECONFIG}"
                //                         END
                if (!fileExists("${WORKSPACE}/source/${env.K8S_MANIFEST_JSON}")) {
                  currentBuild.result = 'ABORTED'
                  error("ABORTED -> Stopping early…. K8s manifests not found [ ${env.K8S_MANIFEST_JSON} ]")
                }
              }
            }
          }
        }
      }
      stage ('Build') {
        when {
          anyOf {
            branch 'development'; branch 'staging'; branch 'qat'; branch 'master';
          }
        }
        steps {		
          container('go') {	  
            dir ('source') {
              script {			
                sh 'sh requirements.txt'
                dir('src') {
				          withCredentials([gitUsernamePassword(credentialsId: 'KNDevOps', gitToolName: 'git-tool')]) {
				            sh 'go build -o main .'
				          }
                }
			        }
            }
          }
        }
      }
      stage('Docker') {
        when {
          beforeAgent true
          expression {
            env.ONLY_MANIFESTS != "true"
          }
          environment name: 'CHANGE_ID', value: ''
          anyOf {
            branch 'development'; branch 'staging'; branch 'qat'; branch 'master'
          }
        }
        steps {
          container('docker') {
            dir('./source') {
              script {
                withCredentials([
                  [$class: 'AmazonWebServicesCredentialsBinding', 
                   accessKeyVariable: 'AWS_ACCESS_KEY_ID', 
                   credentialsId: 'AWS_Credentials', 
                   secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']
                ]) {
                  env.AWS_DEFAULT_REGION = 'us-west-2'
                  sh "aws configure set default.region ${env.AWS_DEFAULT_REGION}"
                  sh "aws ecr create-repository --repository-name service_images/${env.PROJECT_NAME} || true"
                  sh "aws ecr put-lifecycle-policy --repository-name service_images/${env.PROJECT_NAME} --lifecycle-policy-text '{\"rules\":[{\"rulePriority\":1,\"description\":\"Remove dev images - KEEP LAST 2\",\"selection\":{\"tagStatus\":\"tagged\",\"tagPrefixList\":[\"dev\"],\"countType\":\"imageCountMoreThan\",\"countNumber\":2},\"action\":{\"type\":\"expire\"}},{\"rulePriority\":2,\"description\":\"Remove tg-dev images - KEEP LAST 2\",\"selection\":{\"tagStatus\":\"tagged\",\"tagPrefixList\":[\"tg-dev\"],\"countType\":\"imageCountMoreThan\",\"countNumber\":2},\"action\":{\"type\":\"expire\"}},{\"rulePriority\":3,\"description\":\"Remove stage images - KEEP LAST 2\",\"selection\":{\"tagStatus\":\"tagged\",\"tagPrefixList\":[\"stage\"],\"countType\":\"imageCountMoreThan\",\"countNumber\":2},\"action\":{\"type\":\"expire\"}},{\"rulePriority\":4,\"description\":\"Remove tg-stage images - KEEP LAST 2\",\"selection\":{\"tagStatus\":\"tagged\",\"tagPrefixList\":[\"tg-stage\"],\"countType\":\"imageCountMoreThan\",\"countNumber\":2},\"action\":{\"type\":\"expire\"}},{\"rulePriority\":5,\"description\":\"Remove prod images - KEEP LAST 5\",\"selection\":{\"tagStatus\":\"tagged\",\"tagPrefixList\":[\"prod\"],\"countType\":\"imageCountMoreThan\",\"countNumber\":5},\"action\":{\"type\":\"expire\"}},{\"rulePriority\":6,\"description\":\"Remove tg-prod images - KEEP LAST 5\",\"selection\":{\"tagStatus\":\"tagged\",\"tagPrefixList\":[\"tg-prod\"],\"countType\":\"imageCountMoreThan\",\"countNumber\":5},\"action\":{\"type\":\"expire\"}},{\"rulePriority\":7,\"description\":\"Remove qat images - KEEP LAST 3\",\"selection\":{\"tagStatus\":\"tagged\",\"tagPrefixList\":[\"qat\"],\"countType\":\"imageCountMoreThan\",\"countNumber\":3},\"action\":{\"type\":\"expire\"}},{\"rulePriority\":8,\"description\":\"Remove tg-qat images - KEEP LAST 3\",\"selection\":{\"tagStatus\":\"tagged\",\"tagPrefixList\":[\"tg-qat\"],\"countType\":\"imageCountMoreThan\",\"countNumber\":3},\"action\":{\"type\":\"expire\"}},{\"rulePriority\":9,\"description\":\"Remove release images - KEEP FOR 30 DAYS\",\"selection\":{\"tagStatus\":\"tagged\",\"tagPrefixList\":[\"release\"],\"countType\":\"sinceImagePushed\",\"countUnit\":\"days\",\"countNumber\":30},\"action\":{\"type\":\"expire\"}},{\"rulePriority\":10,\"description\":\"Remove tg-release images - KEEP FOR 30 DAYS\",\"selection\":{\"tagStatus\":\"tagged\",\"tagPrefixList\":[\"tg-release\"],\"countType\":\"sinceImagePushed\",\"countUnit\":\"days\",\"countNumber\":30},\"action\":{\"type\":\"expire\"}},{\"rulePriority\":11,\"description\":\"Remove untagged images - KEEP NONE\",\"selection\":{\"tagStatus\":\"untagged\",\"countType\":\"sinceImagePushed\",\"countUnit\":\"days\",\"countNumber\":1},\"action\":{\"type\":\"expire\"}},{\"rulePriority\":12,\"description\":\"Remove pre-prod images - KEEP ONLY 1 DAY\",\"selection\":{\"tagStatus\":\"tagged\",\"tagPrefixList\":[\"pre-prod\"],\"countType\":\"sinceImagePushed\",\"countUnit\":\"days\",\"countNumber\":1},\"action\":{\"type\":\"expire\"}}]}' || true"
                  sh "aws ecr set-repository-policy --repository-name service_images/${env.PROJECT_NAME} --policy-text '{ \"Version\" : \"2008-10-17\",\"Statement\" : [ { \"Sid\" : \"Allow Prod EKS\",\"Effect\" : \"Allow\",\"Principal\" : { \"AWS\" : \"arn:aws:iam::035592488042:root\" }, \"Action\" : [ \"ecr:GetDownloadUrlForLayer\", \"ecr:BatchGetImage\", \"ecr:BatchCheckLayerAvailability\" ] } ] }'"
                  sh(script: """aws ecr get-login-password --region us-west-2 | docker login --username AWS --password-stdin 035592488042.dkr.ecr.us-west-2.amazonaws.com""")
                  
                  // Determine image tag and build
                  def imageTag = params.DeployTarget ? "tg-${env.DOCKER_BUILD_IMAGE_TAG}" : env.DOCKER_BUILD_IMAGE_TAG
                  def fullImageName = "service_images/${env.PROJECT_NAME}:${imageTag}"
                  
                  if (params.DeployTarget) {
                    echo "Target specific deployment selected. Creating image with tg- prefix."
                  } else {
                    echo "Regular deployment. Creating standard image."
                  }
                  
                  // Build Docker image
                  def serviceImage = docker.build(
                    fullImageName,
                    "--network=host --label environment=${env.ENVIRONMENT} --label service=${env.PROJECT_NAME} --label jenkins_build_number='${env.BUILD_NUMBER}' --label git_commit=${env.GIT_COMMIT} --build-arg project_name='${env.PROJECT_NAME}' --build-arg branch_name='${env.BRANCH_NAME}' ."
                  )
                  
                  // ========================================
                  // ORCA SECURITY SCAN - ALL BRANCHES
                  // ========================================
                  if (!params.SKIP_ORCA_SCAN) {
                    echo "Initiating Orca Security Scan for ${env.ENVIRONMENT} environment..."
                    def scanResults = executeOrcaScan(
                      fullImageName,
                      env.ENVIRONMENT,  // Use effective environment instead of branch
                      env.ORCA_API_TOKEN
                    )
                    
                    // Store scan results in environment for later use
                    env.ORCA_SCAN_SUCCESS = scanResults.success.toString()
                    env.ORCA_SCAN_CRITICAL = scanResults.criticalCount.toString()
                    env.ORCA_SCAN_HIGH = scanResults.highCount.toString()
                    env.ORCA_PROJECT = scanResults.project
                  } else {
                    echo "⚠️  ORCA SCAN SKIPPED - User selected to skip security scanning"
                    env.ORCA_SCAN_SUCCESS = "SKIPPED"
                    env.ORCA_PROJECT = "N/A"
                  }
                  
                  // Push to ECR
                  echo "Pushing image to ECR..."
                  docker.withRegistry("https://${env.ECR_HOST}") {
                    serviceImage.push()
                    sh "docker rmi -f ${serviceImage.id} ${env.ECR_HOST}/${serviceImage.id}"
                  }
                  
                  env.PROJECT_DOCKER_IMAGE = "${env.ECR_HOST}/${fullImageName}"
                  
                  echo """
╔════════════════════════════════════════════════════════════════╗
║              DOCKER BUILD & SCAN COMPLETED                    ║
╠════════════════════════════════════════════════════════════════╣
║  Image:        ${env.PROJECT_DOCKER_IMAGE.take(48).padRight(48)}║
║  Environment:  ${env.ENVIRONMENT.padRight(48)}║
║  Orca Project: ${env.ORCA_PROJECT.padRight(48)}║
║  Scan Status:  ${env.ORCA_SCAN_SUCCESS.padRight(48)}║
╚════════════════════════════════════════════════════════════════╝
"""
                }
              }
            }
          }
        }
      }
      stage('Deploy - kubectl') {
        when {
          beforeAgent true
          environment name: 'CHANGE_ID', value: ''
          expression {
            // Deploy to kubectl for dev, staging, qat (not production)
            return env.ENVIRONMENT in ['development', 'staging', 'qat']
          }
        }
        environment {
          PATH = "$PATH:/usr/local/bin"
        }
        steps {
          container('go') {
            dir('./source') {
              script {
                withCredentials([
                [$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AWS_Credentials', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']
              ]){
	                env.AWS_DEFAULT_REGION = 'us-west-2'
	                sh "aws configure set default.region ${env.AWS_DEFAULT_REGION}"
		              configFileProvider([configFile(fileId: env.KUBECONFIG, targetLocation: "./${env.KUBECONFIG}")]) {
                    timeout(time: 10, unit: 'MINUTES') {
                      def k8s_manifest_json = readFile(file: "${env.K8S_MANIFEST_JSON}")
                      def k8s_manifest_rendered = renderTemplate(k8s_manifest_json, ["namespace": "${env.EKS_DEPLOYMENT_NAMESPACE}", "ssl_arn": "${env.AWS_SSL_ARN}"])
                      def k8_manifest_map = parseJsonToMap(k8s_manifest_rendered)
                      if ((!k8_manifest_map.containsKey('skip') || k8_manifest_map.skip != "true") && fileExists("${WORKSPACE}/source/kubernetesfiles/${env.PROJECT_NAME}.yaml")) {
                        echo "reading kubernetes file of the service"
			                  def k8s_manifest_template = readFile("${WORKSPACE}/source/kubernetesfiles/${env.PROJECT_NAME}.yaml").toString()
                        k8s_manifest_rendered = renderTemplate(k8s_manifest_template, k8_manifest_map)
                        sh "cat > ${WORKSPACE}/source/kubernetesfiles/${env.PROJECT_NAME}.yaml <<EOL\n${k8s_manifest_rendered}\nEOL"
                        if (env.PROJECT_DOCKER_IMAGE?.trim()) {
                          sh "sed -i \'s|image.*:.*035592488042.*|image: ${env.PROJECT_DOCKER_IMAGE}|\' ${WORKSPACE}/source/kubernetesfiles/${env.PROJECT_NAME}.yaml"
                        }
                        echo "Kubernetes manifest file"
                        sh "cat kubernetesfiles/${env.PROJECT_NAME}.yaml"
                        echo "Deploying to Kubernetes Cluster - ${env.ENVIRONMENT}"
                        sh "kubectl --kubeconfig=./${env.KUBECONFIG} apply -f kubernetesfiles/${env.PROJECT_NAME}.yaml"
                        push_files_to_s3("${env.PROJECT_NAME}.yaml", "${env.ENVIRONMENT}")
                      } else if (!k8_manifest_map.containsKey('skip') || k8_manifest_map.skip != "true") {
                        currentBuild.result = 'ABORTED'
                        error("ABORTED -> Stopping early…. No Project K8s manifests found [ kubernetesfiles/${env.PROJECT_NAME}.yaml ]")
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
      stage('Deploy - spinnaker') {
        when {
          beforeAgent true
          environment name: 'CHANGE_ID', value: ''
          expression {
            // Deploy to spinnaker for production
            return env.ENVIRONMENT == 'production'
          }
        }
        environment {
          PATH = "$PATH:/usr/local/bin"
        }
        steps {
          container('go') {  
            dir('./source') {
              script {
		                  // Additing common spinnaker config file
                configFileProvider([configFile(fileId: "spinnaker_template", variable: 'file')]) {
                  echo "Moving Spinnaker config to ${env.MANIFEST_FILE_NAME}.json"
                  sh "mv ${file} ${env.MANIFEST_FILE_NAME}.json"
                }
                withCredentials([
                [$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AWS_Credentials', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']
              ]) {
                  timeout(time: 10, unit: 'MINUTES') {
		                sh "whoami"
		                sh "ls /home/jenkins"	
                    def k8s_manifest_json = readFile(file: "${env.K8S_MANIFEST_JSON}")
                    def k8s_manifest_rendered = renderTemplate(k8s_manifest_json, ["namespace": "${env.EKS_DEPLOYMENT_NAMESPACE}", "ssl_arn": "${env.AWS_SSL_ARN}"])
                    def k8_manifest_map = parseJsonToMap(k8s_manifest_rendered)
                    if ((!k8_manifest_map.containsKey('skip') || k8_manifest_map.skip != "true") && fileExists("${WORKSPACE}/source/kubernetesfiles/${env.PROJECT_NAME}.yaml")) {
                      def k8s_manifest_template = readFile("${WORKSPACE}/source/kubernetesfiles/${env.PROJECT_NAME}.yaml").toString()
                      k8s_manifest_rendered = renderTemplate(k8s_manifest_template, k8_manifest_map)
                      sh "cat > ${WORKSPACE}/source/kubernetesfiles/${env.PROJECT_NAME}.yaml <<EOL\n${k8s_manifest_rendered}\nEOL"
                      if (env.PROJECT_DOCKER_IMAGE?.trim()) {
                        sh "sed -i \'s|image.*:.*035592488042.*|image: ${env.PROJECT_DOCKER_IMAGE}|\' ${WORKSPACE}/source/kubernetesfiles/${env.PROJECT_NAME}.yaml"
                      }
                      push_files_to_s3("${env.PROJECT_NAME}.yaml", "${env.ENVIRONMENT}")
                      sh "sed -i \'s|namespace.*:.*${env.EKS_DEPLOYMENT_NAMESPACE}.*|namespace: ${env.SPIN_PROMOTE_TO_ENV_EKS_NAMESPACE}|\' ${WORKSPACE}/source/kubernetesfiles/${env.PROJECT_NAME}.yaml"
                      sh "sed -i \'s|service\\.beta\\.kubernetes\\.io/aws-load-balancer-ssl-cert.*:.*${env.AWS_SSL_ARN}.*|service\\.beta\\.kubernetes\\.io/aws-load-balancer-ssl-cert: ${env.SPIN_PROMOTE_AWS_SSL_ARN}|\' ${WORKSPACE}/source/kubernetesfiles/${env.PROJECT_NAME}.yaml"
                      echo "Kubernetes manifest file"
                      sh "cat kubernetesfiles/${env.PROJECT_NAME}.yaml"
                      echo "Uploading Manifest files to S3"
                      push_files_to_s3("${env.PROJECT_NAME}.yaml", "${env.SPIN_PROMOTE_TO_ENV_NAME}")
                      echo "Triggering Spinnaker Deployment"
                      if (params.DeployTarget && !env.PROJECT_NAME.startsWith("tg-")) {
                        echo "Target deployment: updating PROJECT_NAME to tg-${env.PROJECT_NAME}"
                        env.PROJECT_NAME = "tg-${env.PROJECT_NAME}"
                      }
                      if (fileExists("${WORKSPACE}/source/${env.PROJECT_NAME}.json")) {
                        def output = ''
                        spinnaker "render_pipeline"
                        output = spinnaker "check_application_exists"
                        if (output == "NOT FOUND") {
                          echo " !! NO EXISTING SPINNAKER APPLICATION FOUND !!"
                          echo "<< CREATING NEW SPINNAKER APPLICATION >>"
                          output = spinnaker "create_application"
                          if (output == "SUCCESS") {
                            echo "@@ SUCCESSFULLY CREATED NEW APPLICATION @@"
                          } else {
                            currentBuild.result = 'ABORTED'
                            error("ABORTED -> Stopping early…. New Spinnaker App Creation Failed !! <${output}>")
                          }
                        }
                        echo "<< CREATING/UPDATING SPINNAKER PIPELINE >>"
                        output = spinnaker "save_pipeline"
                        if (output == "SUCCESS") {
                          echo "@@ SUCCESSFULLY UPDATED PIPELINE @@"
                        } else {
                          currentBuild.result = 'ABORTED'
                          error("ABORTED -> Stopping early…. Spinnaker App Pipeline Creation/Updation Failed !! <${output}>")
                        }
                        echo "<< TRIGGERING SPINNAKER PIPELINE EXECUTION >>"
                        output = spinnaker "execute_pipeline"
                        if (output == "SUCCESS") {
                          echo "@@ SUCCESSFULLY EXECUTED PIPELINE @@"
                        } else {
                          currentBuild.result = 'ABORTED'
                          error("ABORTED -> Stopping early…. Spinnaker App Pipeline Execution Failed !! <${output}>")
                        }
                      } else {
                        currentBuild.result = 'ABORTED'
                        error("ABORTED -> Stopping early…. No Project Spinnaker Json found [ ${env.PROJECT_NAME}.json ]")
                      }
                    } else if (!k8_manifest_map.containsKey('skip') || k8_manifest_map.skip != "true") {
                      currentBuild.result = 'ABORTED'
                      error("ABORTED -> Stopping early…. No Project K8s manifests found [ kubernetesfiles/${env.PROJECT_NAME}.yaml ]")
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
    post {
      always {
        script{
          env.CHANGED_LOG = getChangeLogMsgs(currentBuild)
          def result = "${currentBuild.currentResult}"
          
          // Add environment info to notification
          def buildSummary = """
Build #${env.BUILD_NUMBER} - ${result}
Environment: ${env.ENVIRONMENT}
Branch: ${env.BRANCH_NAME}
Orca Scan: ${env.ORCA_SCAN_SUCCESS ?: 'N/A'}
"""
          echo buildSummary
          
          teamsNotifier {
            buildResult = result
          }
        }
      }
      cleanup {
        cleanWs()
      }
    }
  }
}

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

def getChangeLogMsgs(currentBuild) {
  passedBuilds = []
  lastSuccessfulBuild(passedBuilds, currentBuild)
  return getChangeLog(passedBuilds)
}
def lastSuccessfulBuild(passedBuilds, build) {
  if ((build != null) && (build.result != 'SUCCESS')) {
    passedBuilds.add(build)
    lastSuccessfulBuild(passedBuilds, build.getPreviousBuild())
  }
}
def getChangeLog(passedBuilds) {
  def log = ""
  for (int x = 0; x < passedBuilds.size(); x++) {
    def currentBuild = passedBuilds[x];
    def changeLogSets = currentBuild.rawBuild.changeSets
    for (int i = 0; i < changeLogSets.size(); i++) {
      def entries = changeLogSets[i].items
      for (int j = 0; j < entries.length; j++) {
        def entry = entries[j]
        log += "* ${entry.msg} by ${entry.author} \n"
      }
    }
  }
  if (!log) {
    log = "* No new changes"
  }
  return log;
}
def push_files_to_s3(file_name, environment) {
withAWS(region:'us-west-2', credentials:'AWS_Credentials') {	
  s3Upload acl: 'Private', bucket: 'jenkins-kubernetes-deployments', cacheControl: '', file: "${file_name}", metadatas: [''], path: "${environment}/", sseAlgorithm: 'AES256', workingDir: 'kubernetesfiles'
       }
}
def pull_files_from_s3() {
	withAWS(region:'us-west-2', credentials:'AWS_Credentials') {
  s3Download bucket: 'jenkins-kubernetes-deployments', file: "${env.KX_ENVIRONMENT_CONFIGURATION_JENKINS_LOAD_PATH}", path: "release/${KX_ENVIRONMENT_CONFIGURATION_FILE}", force: true
	}
}
def define_environment(branch_environments, other_branch_environment) {
  if (branch_environments.containsKey(env.BRANCH_NAME)) {
    return "${branch_environments[env.BRANCH_NAME]['NAME']}"
  } else {
    return "${branch_environments[other_branch_environment]['NAME']}"
  }
}
def define_next_promote_environment(branch_environments, other_branch_environment) {
  if (branch_environments.containsKey(env.BRANCH_NAME)) {
    return "${branch_environments[branch_environments[env.BRANCH_NAME]['SPIN_PROMOTE_TO_ENV']]['NAME']}"
  } else {
    return "${branch_environments[branch_environments[other_branch_environment]['SPIN_PROMOTE_TO_ENV']]['NAME']}"
  }
}
def define_next_promote_aws_ssl_arn(branch_environments, other_branch_environment) {
  if (branch_environments.containsKey(env.BRANCH_NAME)) {
    return "${branch_environments[branch_environments[env.BRANCH_NAME]['SPIN_PROMOTE_TO_ENV']]['SSL_ARN']}"
  } else {
    return "${branch_environments[branch_environments[other_branch_environment]['SPIN_PROMOTE_TO_ENV']]['SSL_ARN']}"
  }
}
def define_next_promote_environment_eks_namespace(branch_environments, other_branch_environment) {
  if (branch_environments.containsKey(env.BRANCH_NAME)) {
    return "${branch_environments[branch_environments[env.BRANCH_NAME]['SPIN_PROMOTE_TO_ENV']]['EKS_NAMESPACE']}"
  } else {
    return "${branch_environments[branch_environments[other_branch_environment]['SPIN_PROMOTE_TO_ENV']]['EKS_NAMESPACE']}"
  }
}
def binding_environment(environment) {
  if (!fileExists("${KX_ENVIRONMENT_CONFIGURATION_JENKINS_LOAD_PATH}")) {
    pull_files_from_s3();
    if (!fileExists("${KX_ENVIRONMENT_CONFIGURATION_JENKINS_LOAD_PATH}")) {
      currentBuild.result = 'ABORTED'
      env.CHANGED_LOG = "## KX JENKINS ENVIRONMENT FILE MISSING ##"
      error("ABORTED -> Stopping early…. Environment file not found [ ${KX_ENVIRONMENT_CONFIGURATION_FILE} ]")
    }
  }
  echo "Environment File Detected : ${KX_ENVIRONMENT_CONFIGURATION_FILE}"
  load "${KX_ENVIRONMENT_CONFIGURATION_JENKINS_LOAD_PATH}"
  return env."${environment.toUpperCase()}"
}
def define_docker_build_image_tag(branch_environments, other_branch_environment) {
  if (branch_environments.containsKey(env.BRANCH_NAME)) {
    return "${branch_environments[env.BRANCH_NAME]['DOCKER_PREFIX_UNIQUEID']}${env.BUILD_NUMBER}"
  } else {
    return "${branch_environments[other_branch_environment]['DOCKER_PREFIX_UNIQUEID']}${env.BRANCH_NAME}-${env.BUILD_NUMBER}"
  }
}
def define_eks_deployment_namespace(branch_environments, other_branch_environment) {
  if (branch_environments.containsKey(env.BRANCH_NAME)) {
    if (branch_environments[env.BRANCH_NAME]['SHARED_EKS_NAMESPACE'] && branch_environments[env.BRANCH_NAME]['SHARED_EKS_NAMESPACE'] != 'NA') {
      return "${branch_environments[env.BRANCH_NAME]['SHARED_EKS_NAMESPACE']}"
    }
    return "${branch_environments[env.BRANCH_NAME]['EKS_NAMESPACE']}"
  } else {
    if (branch_environments[other_branch_environment]['SHARED_EKS_NAMESPACE'] && branch_environments[other_branch_environment]['SHARED_EKS_NAMESPACE'] != 'NA') {
      return "${branch_environments[env.BRANCH_NAME]['SHARED_EKS_NAMESPACE']}"
    }
    return "${branch_environments[other_branch_environment]['EKS_NAMESPACE']}"
  }
}
def define_aws_ssl_arn(branch_environments, other_branch_environment) {
  if (branch_environments.containsKey(env.BRANCH_NAME)) {
    return "${branch_environments[env.BRANCH_NAME]['SSL_ARN']}"
  } else {
    return "${branch_environments[other_branch_environment]['SSL_ARN']}"
  }
}
def define_kubeconfig(branch_environments, other_branch_environment) {
  if (branch_environments.containsKey(env.BRANCH_NAME)) {
    return "${branch_environments[env.BRANCH_NAME]['EKS_KUBECONFIG']}"
  } else {
    return "${branch_environments[other_branch_environment]['EKS_KUBECONFIG']}"
  }
}
def define_eks_deployment_namespace_toggle(environments_info, environment_binding) {
  echo "TOGGLING EKS_DEPLOYMENT_NAMESPACE FOR ${environment_binding} --> ${environments_info[environment_binding]['EKS_NAMESPACE']}"
  return "${environments_info[environment_binding]['EKS_NAMESPACE']}"
}

def define_kubeconfig_toggle(environments_info, environment_binding) {
  echo "TOGGLING KUBECONFIG FOR ${environment_binding} --> ${environments_info[environment_binding]['EKS_KUBECONFIG']}"
  return "${environments_info[environment_binding]['EKS_KUBECONFIG']}"
}

def definecheckout_to() {
    def branchName = "${env.BRANCH_NAME}"
    if (branchName == "master") {
        return 'staging'
    }
    else {
        return 'development'
    }
}

// ============================================================================
// ENVIRONMENT OVERRIDE HELPER FUNCTIONS
// ============================================================================

/**
 * Maps environment name to corresponding branch name
 */
def mapEnvironmentToBranch(String environment) {
    switch(environment) {
        case 'production':
            return 'master'
        case 'staging':
            return 'staging'
        case 'qat':
            return 'qat'
        case 'development':
            return 'development'
        default:
            return 'development'
    }
}

/**
 * Define Docker image tag based on environment override
 */
def define_docker_build_image_tag_for_env(String environment) {
    def prefix = ''
    switch(environment) {
        case 'production':
            prefix = 'prod-'
            break
        case 'staging':
            prefix = 'stage-'
            break
        case 'qat':
            prefix = 'qat-'
            break
        default:
            prefix = 'dev-'
    }
    return "${prefix}${env.BUILD_NUMBER}"
}

/**
 * Define AWS SSL ARN based on environment override
 */
def define_aws_ssl_arn_for_env(String environment, Map branch_environments) {
    def targetBranch = mapEnvironmentToBranch(environment)
    if (branch_environments.containsKey(targetBranch)) {
        return "${branch_environments[targetBranch]['SSL_ARN']}"
    }
    return "${branch_environments['development']['SSL_ARN']}"
}

/**
 * Define EKS namespace based on environment override
 */
def define_eks_deployment_namespace_for_env(String environment, Map branch_environments) {
    def targetBranch = mapEnvironmentToBranch(environment)
    if (branch_environments.containsKey(targetBranch)) {
        return "${branch_environments[targetBranch]['EKS_NAMESPACE']}"
    }
    return "${branch_environments['development']['EKS_NAMESPACE']}"
}

/**
 * Define kubeconfig based on environment override
 */
def define_kubeconfig_for_env(String environment, Map branch_environments) {
    def targetBranch = mapEnvironmentToBranch(environment)
    if (branch_environments.containsKey(targetBranch)) {
        return "${branch_environments[targetBranch]['EKS_KUBECONFIG']}"
    }
    return "${branch_environments['development']['EKS_KUBECONFIG']}"
}

// ============================================================================
// ORCA SECURITY SCANNING FUNCTIONS
// ============================================================================

/**
 * Determines the Orca Security project based on environment name
 */
def getOrcaProjectForEnvironment(String environment) {
    switch(environment) {
        case 'production':
            return 'production'
        case 'staging':
            return 'staging'
        case 'qat':
            return 'qat'
        case 'development':
            return 'development'
        default:
            return 'development'
    }
}

/**
 * Executes Orca CLI security scan on a Docker image
 */
def executeOrcaScan(String imageName, String environment, String orcaToken) {
    def orcaProject = getOrcaProjectForEnvironment(environment)
    def scanResult = [
        success: false, 
        project: orcaProject, 
        criticalCount: 0, 
        highCount: 0, 
        mediumCount: 0,
        lowCount: 0,
        message: ''
    ]
    
    try {
        echo """
╔════════════════════════════════════════════════════════════════╗
║              EXECUTING ORCA SECURITY SCAN                     ║
╠════════════════════════════════════════════════════════════════╣
║  Image:        ${imageName.take(48).padRight(48)}║
║  Environment:  ${environment.padRight(48)}║
║  Orca Project: ${orcaProject.padRight(48)}║
╚════════════════════════════════════════════════════════════════╝
"""
        
        // Execute Orca scan
        sh """
            orca-cli image scan \
                --project-key ${orcaProject} \
                --api-token ${orcaToken} \
                --format json \
                --output orca-scan-results.json \
                --no-color \
                ${imageName} || true
        """
        
        // Check if results file was created
        if (fileExists('orca-scan-results.json')) {
            def results = readJSON file: 'orca-scan-results.json'
            
            scanResult.success = true
            scanResult.criticalCount = results.critical_count ?: 0
            scanResult.highCount = results.high_count ?: 0
            scanResult.mediumCount = results.medium_count ?: 0
            scanResult.lowCount = results.low_count ?: 0
            scanResult.message = "Scan completed successfully"
            
            echo """
╔════════════════════════════════════════════════════════════════╗
║                    SCAN RESULTS SUMMARY                       ║
╠════════════════════════════════════════════════════════════════╣
║  Status:       ${scanResult.message.padRight(48)}║
║  Critical:     ${scanResult.criticalCount.toString().padRight(48)}║
║  High:         ${scanResult.highCount.toString().padRight(48)}║
║  Medium:       ${scanResult.mediumCount.toString().padRight(48)}║
║  Low:          ${scanResult.lowCount.toString().padRight(48)}║
╠════════════════════════════════════════════════════════════════╣
║  Results sent to Orca project: ${orcaProject.padRight(29)}║
╚════════════════════════════════════════════════════════════════╝
"""
            
            // Archive scan results
            archiveArtifacts artifacts: 'orca-scan-results.json', allowEmptyArchive: true
            
            // Optional: Fail build on critical vulnerabilities
            if (scanResult.criticalCount > 0) {
                echo "⚠️  WARNING: ${scanResult.criticalCount} critical vulnerabilities found!"
                // Uncomment to fail build:
                // error("Build failed due to ${scanResult.criticalCount} critical vulnerabilities")
            }
            
        } else {
            scanResult.message = "Scan results file not found"
            echo "⚠️  Warning: ${scanResult.message}"
        }
        
    } catch (Exception e) {
        scanResult.success = false
        scanResult.message = "Scan failed: ${e.message}"
        echo "❌ Error during Orca scan: ${e.message}"
    }
    
    return scanResult
}

return this
