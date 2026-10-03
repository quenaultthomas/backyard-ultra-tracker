import { reserveAuxRoles } from '../comptes/reserve-aux-roles.guard';
import { estAdminMaster, estAdministrateur } from '../comptes/roles';

export const reserveAuxAdministrateurs = reserveAuxRoles(estAdministrateur);
export const reserveAAdminMaster = reserveAuxRoles(estAdminMaster);
