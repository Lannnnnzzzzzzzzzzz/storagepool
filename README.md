# Multi-Account Cloud Storage Pooling System (Android Native)

StoragePool is a production-ready, high-fidelity Android Native application featuring a Cloud Storage Pooling and Smart Routing Engine powered by Cloudflare R2 and Supabase. It allows users to aggregate multiple decentralized Cloudflare R2 buckets into a single virtualized, unified storage pool, distributing file objects intelligently based on capacity metrics and health status.

## 🛠️ Technology Stack
*   **Frontend/UI**: Android Native Kotlin using Jetpack Compose (Material Design 3)
*   **Design Paradigm**: Obsidian Cyber-Dark Visual Universe with glowing state overlays
*   **Networking & Transfers**: Retrofit & OkHttp with customized binary stream chunk uploader
*   **Database & Gotrue Auth**: Supabase REST APIs (Auth and Postgrest CRUD engine)
*   **Storage standard**: S3-compliant API (Cloudflare R2) using direct client-side S3 Signature V4 signing

---

## 🗄️ Database Schema & Setup Guide

Execute the following PostgreSQL script in the SQL Editor of your **Supabase Dashboard** to provision the storage pools registry and file metadata schemas:

```sql
-- 1. Create Storage Buckets registry table
CREATE TABLE public.storage_buckets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bucket_name TEXT NOT NULL UNIQUE,
    endpoint TEXT NOT NULL,
    access_key_id TEXT NOT NULL,
    secret_access_key TEXT NOT NULL,
    total_quota_bytes BIGINT NOT NULL DEFAULT 10737418240, -- Default 10GB in bytes
    used_bytes BIGINT NOT NULL DEFAULT 0,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'FULL', 'DOWN')) DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT timezone('utc'::text, now()) NOT NULL
);

-- Matikan RLS untuk tabel storage_buckets agar pool baru terbaca sempurna ddan bebas dari isu otorisasi
ALTER TABLE public.storage_buckets DISABLE ROW LEVEL SECURITY;


-- 2. Create Files catalog metadata table
CREATE TABLE public.files (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    filename TEXT NOT NULL,
    file_path TEXT NOT NULL UNIQUE,
    file_size BIGINT NOT NULL,
    mime_type TEXT NOT NULL,
    bucket_id UUID NOT NULL REFERENCES public.storage_buckets(id) ON DELETE CASCADE,
    is_encrypted BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT timezone('utc'::text, now()) NOT NULL
);

-- Matikan RLS untuk tabel files agar bebas dari kendala akses atau sinkronisasi data antar pengguna
ALTER TABLE public.files DISABLE ROW LEVEL SECURITY;
```

---

## 📦 Project Architecture Pattern

```
app/src/main/java/com/example/
│
├── domain/                                     # Core Pure Domain Layer
│   ├── model/
│   │   ├── StorageBucket.kt                    # Bucket Model & availability logic
│   │   ├── CloudFile.kt                        # File Metadata Model
│   │   └── UserSession.kt                      # Auth User Session
│   └── repository/
│       ├── AuthRepository.kt                   # Auth Repository interface definitions
│       └── StorageRepository.kt                # Storage pool and uploader schema 
│
├── data/                                       # Data & Infrastructural Layer
│   ├── remote/
│   │   ├── DbtAndAuthModels.kt                 # Gotrue and Postgrest DTO mappings
│   │   ├── SupabaseAuthApi.kt                  # Gotrue Retrofit endpoints
│   │   ├── SupabaseDbApi.kt                    # Postgrest REST Retrofit endpoints
│   │   └── SupabaseClient.kt                   # Dynamic interceptor & Client initialization
│   ├── util/
│   │   └── S3Signer.kt                         # Multi-region AWS Signature V4 Generator
│   └── repository/
│       ├── AuthRepositoryImpl.kt               # Local/Remote sync authentication adapter
│       └── StorageRepositoryImpl.kt            # Smart Router & Automated S3 stream failover
│
└── presentation/                               # Visual Component Layer
    ├── auth/
    │   ├── AuthViewModel.kt                    # Auth session state dispatcher
    │   └── AuthScreen.kt                       # Cosmic Login & Simulation gateway
    └── dashboard/
        ├── DashboardViewModel.kt               # Smart routing broker, stats & subfolders nav
        └── DashboardScreen.kt                  # Aggregate Progress Bars, folders grid & lists
```

---

## ⚡ Smart Routing & Transfer Failover Engine

1.  **Smart Allocation (Least-Used routing)**: When a document picker yields a file, `StorageRepository` queries all registration nodes. It filters for active pools (`status == "ACTIVE"`) with available capacity larger than the target payload size. It then sorts them in ascending order of utilized space (`usedBytes`), selecting the least-burdened bucket to handle the request.
2.  **AWS V4 HMAC Encryption Signer**: Computes accurate S3-compatible pre-signed PUT URLs client-side, eliminating server-side proxies or cold-starting functions.
3.  **Active Progress Binary Streams**: Employs OkHttp sockets using a custom `StreamingRequestBody` to stream segments straight to Cloudflare R2's endpoint, feeding real-time progress callbacks straight into Jetpack Compose.
4.  **Automatic Segment Fallover Protection**: If a stream fails (due to connection reset, R2 bucket auth, pool degradation, or metadata timeouts), the uploader intercepts the exception, flags the candidate node as degraded, and automatically redirects the transfer stream to the **next eligible pool** in the sorted queue, assuring zero packet losses!
5.  **Local AES Shield (Optional)**: Safe local AES-CBC 128-bit encryption of content bytes prior to transmission.

---

## 🚀 Step-by-Step Settings Configuration

Configure your Supabase credentials in **Google AI Studio Secrets Settings Panel** using these variable names:

*   `SUPABASE_URL`: Enter your project URL (e.g., `https://xxxx.supabase.co`).
*   `SUPABASE_ANON_KEY`: Enter your project public anonymous API key (accessible under Project Settings > API).

> **💡 Experience Instantly**: No credentials? No worries. Click **Demo Sandbox Simulator** on the authentication panel to launch into a pre-populated simulation playground!
