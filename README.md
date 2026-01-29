This for ORCA
YES! Exactly correct! 🎯
Here's the complete flow:
📊 Complete Flow Diagram
For Development Branch:
Global Pipeline (development branch)
├── Build Stage (compiles Go code)
├── Docker Stage
│   ├── Build Docker image
│   ├── Run INLINE orca-cli scan ✓ (scans immediately)
│   │   └─> Results sent to Orca "development" project
│   └── Push image to ECR
└── Deploy Stage
For Staging/QAT/Master Branches:
Global Pipeline (staging/qat/master branch)
├── Build Stage (compiles Go code)
├── Docker Stage
│   ├── Build Docker image
│   ├── Push image to ECR
│   └── Trigger orcascanstageqatprod pipeline ✓ (async call)
│       └─> Global pipeline continues (doesn't wait)
├── Deploy Stage (continues immediately)
└── Done

Meanwhile, in parallel...

orcascanstageqatprod Pipeline (runs separately)
├── Validate Parameters
├── Authenticate with ECR
├── Check image exists
├── Check if archived
├── Run detailed orca-cli scan
├── Parse results
└── Send to Orca Dashboard
    ├─> staging branch → "staging" project
    ├─> qat branch → "qat" project
    └─> master branch → "prod" project

🎯 Exact Behavior Per Branch
BranchGlobal Pipeline ActionSeparate Pipeline Called?Orca ProjectBlocks Deployment?developmentInline scan in Docker stage❌ NOdevelopment⚠️ Can block (if scan fails)stagingTriggers orcascanstageqatprod✅ YESstaging✅ NO (async)qatTriggers orcascanstageqatprod✅ YESqat✅ NO (async)masterTriggers orcascanstageqatprod✅ YESprod✅ NO (async)

📍 Where Results Appear in Orca Dashboard
After scans complete, you'll see results here:
Orca Dashboard
├── AppSec
│   └── Image Security
│       ├── development project ← development branch images
│       ├── staging project     ← staging branch images
│       ├── qat project         ← qat branch images
│       └── prod project        ← master branch images
URL: https://app.orca.security/image-security
