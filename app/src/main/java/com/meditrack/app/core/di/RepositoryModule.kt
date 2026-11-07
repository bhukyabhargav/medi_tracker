package com.meditrack.app.core.di

import com.meditrack.app.data.repository.CaregiverRepositoryImpl
import com.meditrack.app.data.repository.DoseRepositoryImpl
import com.meditrack.app.data.repository.FakeAuthRepository
import com.meditrack.app.data.repository.FirebaseAuthRepository
import com.meditrack.app.data.repository.MedicineRepositoryImpl
import com.meditrack.app.data.repository.OutboxRepositoryImpl
import com.meditrack.app.data.repository.VerificationRepositoryImpl
import com.meditrack.app.domain.repository.AuthRepository
import com.meditrack.app.domain.repository.CaregiverRepository
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.repository.OutboxRepository
import com.meditrack.app.domain.repository.VerificationRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: FirebaseAuthRepository): AuthRepository

    @Binds
    @Singleton
    abstract fun bindMedicineRepository(impl: MedicineRepositoryImpl): MedicineRepository

    @Binds
    @Singleton
    abstract fun bindDoseRepository(impl: DoseRepositoryImpl): DoseRepository

    @Binds
    @Singleton
    abstract fun bindVerificationRepository(impl: VerificationRepositoryImpl): VerificationRepository

    @Binds
    @Singleton
    abstract fun bindCaregiverRepository(impl: CaregiverRepositoryImpl): CaregiverRepository

    @Binds
    @Singleton
    abstract fun bindOutboxRepository(impl: OutboxRepositoryImpl): OutboxRepository

    @Binds
    @Singleton
    abstract fun bindDoctorRepository(impl: com.meditrack.app.data.repository.DoctorRepositoryImpl): com.meditrack.app.domain.repository.DoctorRepository

    @Binds
    @Singleton
    abstract fun bindProposalRepository(impl: com.meditrack.app.data.repository.ProposalRepositoryImpl): com.meditrack.app.domain.repository.ProposalRepository
}
