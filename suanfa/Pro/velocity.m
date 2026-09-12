function[A,B]=velocity(Us,Uw)

    hw=Uw(:,:,1);uw=Uw(:,:,2)./Uw(:,:,1);vw=Uw(:,:,3)./Uw(:,:,1);
    hs=Us(:,:,1);us=Us(:,:,2)./Us(:,:,1);vs=Us(:,:,3)./Us(:,:,1);
    I=hw<=0;uw(I)=0;vw(I)=0;I=hs<=0;us(I)=0;vs(I)=0;

    A=sqrt(uw.^2+vw.^2);B=sqrt(us.^2+vs.^2);