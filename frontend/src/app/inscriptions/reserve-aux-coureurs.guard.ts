import { reserveAuxRoles } from '../comptes/reserve-aux-roles.guard';
import { estCoureur } from '../comptes/roles';

export const reserveAuxCoureurs = reserveAuxRoles(estCoureur);
