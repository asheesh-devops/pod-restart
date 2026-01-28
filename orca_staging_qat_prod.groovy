/*************************************************************************
 *
 * KLEAREXPRESS CONFIDENTIAL
 * __________________
 *
 *  Copyright (c) 2018 - 2018 KlearExpress Corporation.
 *  All Rights Reserved.
 *
 * Orca Security Scan Pipeline - Staging Environment Only
 * 
 * This pipeline scans Docker images using Orca Security CLI and sends
 * results to the Orca dashboard. It ONLY scans staging environment images.
 * 
 * Usage:
 *   orcascanstageqatprod([
 *     DOCKER_IMAGE: '035592488042.dkr.ecr.us-west-2.amazonaws.com/service_images/myapp:stage-123'
 *   ])
 */

final kxlib = library('kxlib@ashishvorca')

def call(Map pipelineParams) {
  // Skip if triggered by branch indexing
  if (currentBuild.rawBuild.getCauses().toString().contains('BranchIndexingCause')) {
    print "INFO: Build skipped due to trigger being Branch Indexing"
    return
  }

  // Set node type based on branch
  def karpenter_node = (env.BRANCH_NAME == 'master') ? 'karpenter-prod' : 'karpenter'

  // Pod configuration with docker container for Orca CLI
  def podYaml = """
apiVersion: v1
kind: Pod
spec:
  containers:
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
  """

  pipeline {
    agent {
      kubernetes {
        yaml podYaml
      }
    }
    
    parameters {
      string(
        name: 'DOCKER_IMAGE',
        defaultValue: '',
        description: 'Full Docker image name with STAGING tag (e.g., 035592488042.dkr.ecr.us-west-2.amazonaws.com/service_images/myapp:stage-123)'
      )
      booleanParam(
        name: 'SKIP_SCAN',
        defaultValue: false,
        description: 'Skip Orca security scanning'
      )
    }
    
    options {
      buildDiscarder(logRotator(numToKeepStr: '10', daysToKeepStr: '30'))
      disableConcurrentBuilds abortPrevious: true
      timeout(time: 30, unit: 'MINUTES')
    }
    
    environment {
      ORCA_API_TOKEN = credentials('orca-api-token')
      AWS_DEFAULT_REGION = 'us-west-2'
    }
    
    stages {
      stage('Validate Parameters') {
        steps {
          script {
            if (!params.DOCKER_IMAGE?.trim()) {
              error("DOCKER_IMAGE parameter is required. Please provide the full Docker image name.")
            }
            
            // Check if image is from STAGING environment ONLY
            def isStaging = params.DOCKER_IMAGE.contains(':stage-') || 
                           params.DOCKER_IMAGE.contains(':tg-stage-')
            
            if (!isStaging) {
              echo """
╔════════════════════════════════════════════════════════════════╗
║              SKIPPING SCAN - NOT STAGING IMAGE                ║
╠════════════════════════════════════════════════════════════════╣
║  Image:         ${params.DOCKER_IMAGE.take(51).padRight(51)}║
║  Reason:        Only STAGING images are scanned               ║
║  Expected Tag:  Must contain ':stage-' or ':tg-stage-'       ║
╚════════════════════════════════════════════════════════════════╝
"""
              currentBuild.result = 'SUCCESS'
              currentBuild.description = "Skipped - Not a staging image"
              return
            }
            
            echo """
╔════════════════════════════════════════════════════════════════╗
║              ORCA SCAN CONFIGURATION - STAGING ONLY           ║
╠════════════════════════════════════════════════════════════════╣
║  Docker Image:  ${params.DOCKER_IMAGE.take(52).padRight(52)}║
║  Environment:   STAGING ✓                                     ║
║  Orca Project:  staging                                       ║
║  Skip Scan:     ${params.SKIP_SCAN.toString().padRight(52)}║
╚════════════════════════════════════════════════════════════════╝
"""
          }
        }
      }
      
      stage('Orca Security Scan') {
        when {
          expression { params.SKIP_SCAN != true }
        }
        steps {
          container('docker') {
            script {
              echo "Starting Orca security scan for staging environment..."
              
              // Authenticate with ECR BEFORE scanning
              withCredentials([
                [$class: 'AmazonWebServicesCredentialsBinding', 
                 accessKeyVariable: 'AWS_ACCESS_KEY_ID', 
                 credentialsId: 'AWS_Credentials', 
                 secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']
              ]) {
                echo "Configuring AWS credentials..."
                sh "aws configure set default.region ${env.AWS_DEFAULT_REGION}"
                
                echo "Authenticating with ECR..."
                sh """
                  aws ecr get-login-password --region ${env.AWS_DEFAULT_REGION} | \\
                  docker login --username AWS --password-stdin 035592488042.dkr.ecr.us-west-2.amazonaws.com
                """
                
                echo "✓ ECR authentication successful"
                
                // Extract repository name and tag from image
                // Format: 035592488042.dkr.ecr.us-west-2.amazonaws.com/service_images/kn-auto-annotator:stage-16
                def imageParts = params.DOCKER_IMAGE.tokenize('/')
                def lastPart = imageParts[-1]  // kn-auto-annotator:stage-16
                def repoAndTag = lastPart.tokenize(':')
                def repoName = repoAndTag[0]  // kn-auto-annotator
                def imageTag = repoAndTag.size() > 1 ? repoAndTag[1] : 'latest'  // stage-16
                
                // Build full repository path without the tag
                def repository = imageParts[1..-2].join('/') + '/' + repoName  // service_images/kn-auto-annotator
                
                echo "Checking image status in ECR..."
                echo "  Repository: ${repository}"
                echo "  Tag: ${imageTag}"
                
                // Check if image is archived (in Glacier)
                def archiveCheck = sh(
                  script: """
                    aws ecr describe-images \\
                      --repository-name ${repository} \\
                      --image-ids imageTag=${imageTag} \\
                      --region us-west-2 \\
                      --query 'imageDetails[0].imageManifestMediaType' \\
                      --output text 2>&1 || echo 'ERROR'
                  """,
                  returnStdout: true
                ).trim()
                
                if (archiveCheck.contains('ERROR') || archiveCheck.contains('ImageNotFoundException')) {
                  error("""
❌ Image not found in ECR: ${params.DOCKER_IMAGE}

The image tag '${imageTag}' does not exist in repository '${repository}'.

Action: Verify the image tag is correct.
""")
                }
                
                // Check if image is archived
                def lifecycleCheck = sh(
                  script: """
                    aws ecr describe-images \\
                      --repository-name ${repository} \\
                      --image-ids imageTag=${imageTag} \\
                      --region us-west-2 \\
                      --output json 2>&1
                  """,
                  returnStdout: true
                ).trim()
                
                if (lifecycleCheck.contains('"imageScanStatus"') && 
                    lifecycleCheck.contains('SCAN_ELIGIBILITY_EXPIRED')) {
                  echo """
⚠️  WARNING: Image may be archived or scan-ineligible
Repository: ${repository}
Tag: ${imageTag}

This might cause scan failures. Consider:
  1. Pushing the image again to make it active
  2. Using a more recent image tag
"""
                }
                
                // Verify Docker can access the manifest
                echo "Verifying Docker can access image..."
                def manifestCheck = sh(
                  script: "docker manifest inspect ${params.DOCKER_IMAGE} > /dev/null 2>&1 && echo 'SUCCESS' || echo 'FAILED'",
                  returnStdout: true
                ).trim()
                
                if (manifestCheck == 'FAILED') {
                  error("""
❌ Docker cannot access image: ${params.DOCKER_IMAGE}

Possible causes:
  1. Image is in ARCHIVED state (moved to Glacier storage)
  2. Image layers have been deleted
  3. ECR authentication failed (though login succeeded)
  
To check archive status:
  aws ecr describe-images --repository-name ${repository} --image-ids imageTag=${imageTag} --region us-west-2

To restore archived image:
  1. The image must be restored from Glacier (can take hours)
  2. Or use a newer, non-archived image tag
""")
                }
                
                echo "✓ Image is accessible and ready for scanning"
                
                // Execute Orca scan
                def scanResults = executeOrcaScan(
                  params.DOCKER_IMAGE,
                  'staging',
                  env.ORCA_API_TOKEN
                )
                
                // Store results in environment variables
                env.ORCA_SCAN_SUCCESS = scanResults.success.toString()
                env.ORCA_SCAN_CRITICAL = scanResults.criticalCount.toString()
                env.ORCA_SCAN_HIGH = scanResults.highCount.toString()
                env.ORCA_SCAN_MEDIUM = scanResults.mediumCount.toString()
                env.ORCA_SCAN_LOW = scanResults.lowCount.toString()
                env.ORCA_SCAN_INFO = scanResults.informationalCount.toString()
                env.ORCA_SCAN_TOTAL = scanResults.totalVulnerabilities.toString()
                env.ORCA_PROJECT = scanResults.project
                env.ORCA_SCAN_ID = scanResults.scanId
                env.ORCA_SCAN_URL = scanResults.scanUrl
                
                echo """
╔════════════════════════════════════════════════════════════════╗
║              ORCA SCAN COMPLETED                              ║
╠════════════════════════════════════════════════════════════════╣
║  Status:       ${env.ORCA_SCAN_SUCCESS.padRight(48)}║
║  Project:      ${env.ORCA_PROJECT.padRight(48)}║
║  Scan ID:      ${env.ORCA_SCAN_ID.take(48).padRight(48)}║
╠════════════════════════════════════════════════════════════════╣
║  Critical:     ${env.ORCA_SCAN_CRITICAL.padRight(48)}║
║  High:         ${env.ORCA_SCAN_HIGH.padRight(48)}║
║  Medium:       ${env.ORCA_SCAN_MEDIUM.padRight(48)}║
║  Low:          ${env.ORCA_SCAN_LOW.padRight(48)}║
║  Info:         ${env.ORCA_SCAN_INFO.padRight(48)}║
║  Total:        ${env.ORCA_SCAN_TOTAL.padRight(48)}║
╠════════════════════════════════════════════════════════════════╣
║  Dashboard:    ${env.ORCA_SCAN_URL.take(48).padRight(48)}║
╚════════════════════════════════════════════════════════════════╝
"""
              }
            }
          }
        }
      }
    }
    
    post {
      always {
        script {
          def result = "${currentBuild.currentResult}"
          def buildSummary = """
╔════════════════════════════════════════════════════════════════╗
║              BUILD SUMMARY                                    ║
╠════════════════════════════════════════════════════════════════╣
║  Build:        #${env.BUILD_NUMBER} - ${result.padRight(39)}║
║  Image:        ${params.DOCKER_IMAGE.take(48).padRight(48)}║
║  Scan Status:  ${env.ORCA_SCAN_SUCCESS ?: 'NOT_RUN'.padRight(48)}║
"""
          
          if (env.ORCA_SCAN_SUCCESS == 'true') {
            buildSummary += """║  Vulnerabilities: Critical: ${env.ORCA_SCAN_CRITICAL}, High: ${env.ORCA_SCAN_HIGH}, Medium: ${env.ORCA_SCAN_MEDIUM}${' '.padRight(13)}║
║  Dashboard:    ${env.ORCA_SCAN_URL.take(48).padRight(48)}║
"""
          }
          
          buildSummary += """╚════════════════════════════════════════════════════════════════╝"""
          
          echo buildSummary
        }
      }
      cleanup {
        cleanWs()
      }
    }
  }
}

// ============================================================================
// ORCA SECURITY SCANNING FUNCTIONS
// ============================================================================

/**
 * Executes Orca CLI security scan on a Docker image
 * Sends results to Orca dashboard
 * 
 * @param imageName - Full Docker image name with tag
 * @param orcaProject - Orca project key
 * @param orcaToken - Orca API token
 * @return Map containing scan results
 */
def executeOrcaScan(String imageName, String orcaProject, String orcaToken) {
    def scanResult = [
        success: false, 
        project: orcaProject, 
        criticalCount: 0, 
        highCount: 0, 
        mediumCount: 0,
        lowCount: 0,
        informationalCount: 0,
        totalVulnerabilities: 0,
        message: '',
        scanId: '',
        scanUrl: ''
    ]
    
    try {
        echo """
╔════════════════════════════════════════════════════════════════╗
║              ORCA SECURITY SCAN                               ║
╠════════════════════════════════════════════════════════════════╣
║  Image:        ${imageName.take(48).padRight(48)}║
║  Project:      ${orcaProject.padRight(48)}║
║  Registry:     ECR (AWS)                                      ║
╚════════════════════════════════════════════════════════════════╝
"""
        
        // Verify Orca CLI is available
        def cliCheck = sh(
            script: 'which orca-cli || echo "NOT_FOUND"', 
            returnStdout: true
        ).trim()
        
        if (cliCheck == "NOT_FOUND") {
            echo "⚠️  Orca CLI not found. Installing..."
            installOrcaCLI()
        } else {
            def version = sh(
                script: 'orca-cli --version 2>&1 || echo "unknown"', 
                returnStdout: true
            ).trim()
            echo "✓ Orca CLI found: ${version}"
        }
        
        // Clean up previous scan results
        sh 'rm -f orca-scan-*.json orca-cli.log'
        
        // Execute Orca scan
        echo "Executing Orca image scan..."
        def scanCommand = """
            orca-cli image scan \\
                "${imageName}" \\
                --project-key "${orcaProject}" \\
                --api-token "${orcaToken}" \\
                --format json \\
                --output orca-scan-results.json \\
                --no-color \\
                --timeout 900s \\
                --exit-code 0
        """
        
        def scanExitCode = sh(script: scanCommand, returnStatus: true)
        
        echo "Scan execution completed with exit code: ${scanExitCode}"
        
        // Parse scan results
        if (fileExists('orca-scan-results.json')) {
            def resultsFile = readFile('orca-scan-results.json').trim()
            
            if (resultsFile.isEmpty()) {
                echo "⚠️  Warning: Results file is empty"
                scanResult.message = "Scan completed but results file is empty"
            } else {
                echo "Parsing scan results..."
                
                try {
                    def results = readJSON text: resultsFile
                    
                    // Parse vulnerability counts from different JSON formats
                    if (results.details) {
                        def details = results.details
                        scanResult.criticalCount = details.num_critical ?: 0
                        scanResult.highCount = details.num_high ?: 0
                        scanResult.mediumCount = details.num_medium ?: 0
                        scanResult.lowCount = details.num_low ?: 0
                        scanResult.informationalCount = details.num_informational ?: 0
                    } else if (results.summary) {
                        def summary = results.summary
                        scanResult.criticalCount = summary.critical ?: 0
                        scanResult.highCount = summary.high ?: 0
                        scanResult.mediumCount = summary.medium ?: 0
                        scanResult.lowCount = summary.low ?: 0
                        scanResult.informationalCount = summary.informational ?: 0
                    } else if (results.vulnerabilities) {
                        scanResult.criticalCount = results.vulnerabilities.count { it.severity?.toUpperCase() == 'CRITICAL' }
                        scanResult.highCount = results.vulnerabilities.count { it.severity?.toUpperCase() == 'HIGH' }
                        scanResult.mediumCount = results.vulnerabilities.count { it.severity?.toUpperCase() == 'MEDIUM' }
                        scanResult.lowCount = results.vulnerabilities.count { it.severity?.toUpperCase() == 'LOW' }
                        scanResult.informationalCount = results.vulnerabilities.count { it.severity?.toUpperCase() == 'INFORMATIONAL' }
                    }
                    
                    scanResult.totalVulnerabilities = scanResult.criticalCount + 
                                                      scanResult.highCount + 
                                                      scanResult.mediumCount + 
                                                      scanResult.lowCount + 
                                                      scanResult.informationalCount
                    
                    scanResult.scanId = results.scan_id ?: results.id ?: results.asset_id ?: 'N/A'
                    
                    if (scanResult.scanId != 'N/A') {
                        scanResult.scanUrl = "https://app.orca.security/scans/${scanResult.scanId}"
                    } else {
                        scanResult.scanUrl = "https://app.orca.security/image-security"
                    }
                    
                    scanResult.success = true
                    scanResult.message = "Scan completed successfully"
                    
                    echo "✓ Successfully parsed scan results"
                    
                } catch (Exception parseEx) {
                    echo "⚠️  JSON parsing error: ${parseEx.message}"
                    echo "Raw content (first 500 chars): ${resultsFile.take(500)}"
                    
                    scanResult.success = true
                    scanResult.message = "Scan completed but parsing failed"
                }
            }
            
            // Archive results
            archiveArtifacts artifacts: 'orca-scan-results.json', allowEmptyArchive: true
            
        } else {
            echo "❌ Error: Results file not created"
            scanResult.message = "Scan output file not found"
            
            def errorLog = sh(
                script: 'cat orca-cli.log 2>/dev/null || echo "No log"',
                returnStdout: true
            ).trim()
            
            if (errorLog != "No log") {
                echo "Error log:\n${errorLog}"
            }
        }
        
        // Display results
        echo """
╔════════════════════════════════════════════════════════════════╗
║                    SCAN RESULTS                               ║
╠════════════════════════════════════════════════════════════════╣
║  Status:       ${scanResult.message.padRight(48)}║
║  Scan ID:      ${scanResult.scanId.take(48).padRight(48)}║
╠════════════════════════════════════════════════════════════════╣
║  🔴 Critical:  ${scanResult.criticalCount.toString().padRight(48)}║
║  🟠 High:      ${scanResult.highCount.toString().padRight(48)}║
║  🟡 Medium:    ${scanResult.mediumCount.toString().padRight(48)}║
║  🟢 Low:       ${scanResult.lowCount.toString().padRight(48)}║
║  ℹ️  Info:      ${scanResult.informationalCount.toString().padRight(48)}║
╠════════════════════════════════════════════════════════════════╣
║  Total:        ${scanResult.totalVulnerabilities.toString().padRight(48)}║
╠════════════════════════════════════════════════════════════════╣
║  View in Orca: ${scanResult.scanUrl.take(48).padRight(48)}║
╚════════════════════════════════════════════════════════════════╝
"""
        
        if (scanResult.criticalCount > 0) {
            echo """
⚠️  ═══════════════════════════════════════════════════════════════
⚠️  WARNING: ${scanResult.criticalCount} CRITICAL vulnerabilities found!
⚠️  Review in Orca: ${scanResult.scanUrl}
⚠️  ═══════════════════════════════════════════════════════════════
"""
        }
        
        if (scanResult.success) {
            echo "✓ Results sent to Orca: AppSec → Image Security → ${orcaProject}"
        }
        
    } catch (Exception e) {
        scanResult.success = false
        scanResult.message = "Scan failed with exception"
        
        echo """
❌ ═══════════════════════════════════════════════════════════════
❌ ORCA SCAN FAILED: ${e.message}
❌ 
❌ Check:
❌   1. Orca API token is valid
❌   2. Network connectivity to api.orca.security
❌   3. Image exists and is not archived: ${imageName}
❌   4. Project '${orcaProject}' exists in Orca
❌   5. ECR authentication is working
❌ ═══════════════════════════════════════════════════════════════
"""
        
        currentBuild.result = 'UNSTABLE'
    }
    
    return scanResult
}

/**
 * Install Orca CLI
 */
def installOrcaCLI() {
    echo "Installing Orca CLI..."
    try {
        sh '''
            set -e
            curl -sfL 'https://get.orca.security/cli' | bash
            orca-cli --version
            echo "✓ Orca CLI installed"
        '''
    } catch (Exception e) {
        echo "❌ Failed to install Orca CLI: ${e.message}"
        throw e
    }
}

return this
